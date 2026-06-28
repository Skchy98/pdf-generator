package com.erp.infrastructure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.erp.infrastructure.model.BulkGenerationRequest;
import com.erp.infrastructure.model.JobStatusResponse;
import com.erp.infrastructure.model.SingleGenerationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final RemoteRenderEngine renderEngine;
    private final CloudStorageService storageService;
    private final DocumentModifierService modifierService;
    private final StringRedisTemplate redisTemplate;

    private final ExecutorService multiThreadedWorkerPool = Executors.newFixedThreadPool(30);
    private static final int CHUNK_THRESHOLD = 100;
    private static final String REDIS_STATUS_PREFIX = "pdf_job_status:";
    private static final String REDIS_URL_PREFIX = "pdf_job_url:";

    public String processSynchronousDocument(SingleGenerationRequest request) {
        List<Map<String, Object>> segmentedPayloads = evaluateAndChunkData(request.getExtractionPayload());
        byte[] completeBinary;

        try {
            if (segmentedPayloads.size() == 1) {
                completeBinary = renderEngine.executeRemoteRender(request.getStructuralTemplateId(), segmentedPayloads.get(0));
            } else {
                log.warn("Document {} exceeds size threshold. Partitioning into {} tasks to avoid browser memory issues.", request.getDocumentId(), segmentedPayloads.size());
                List<byte[]> structuralParts = new ArrayList<>();
                for (Map<String, Object> partition : segmentedPayloads) {
                    structuralParts.add(renderEngine.executeRemoteRender(request.getStructuralTemplateId(), partition));
                }
                completeBinary = modifierService.mergeBinaryFragments(structuralParts);
            }

            byte[] securelySignedBinary = modifierService.applyCryptographicSignature(completeBinary);
            String storageObjectKey = "compiled-archives/" + request.getDocumentId() + ".pdf";
            return storageService.commitToStorageAndPresign(storageObjectKey, securelySignedBinary);

        } catch (Exception ex) {
            log.error("Execution breakdown occurred while processing document {}", request.getDocumentId(), ex);
            throw new RuntimeException("Internal generation engine failure", ex);
        }
    }

    public String registerAndSubmitBulkJob(BulkGenerationRequest batchRequest) {
        String uniqueJobId = UUID.randomUUID().toString();
        String statusTrackingKey = REDIS_STATUS_PREFIX + uniqueJobId;

        redisTemplate.opsForValue().set(statusTrackingKey, "0/" + batchRequest.getDocuments().size());

        CompletableFuture.runAsync(() -> executeAsyncBulkPipeline(uniqueJobId, batchRequest), multiThreadedWorkerPool);

        return uniqueJobId;
    }

    private void executeAsyncBulkPipeline(String uniqueJobId, BulkGenerationRequest batchRequest) {
        int maximumCount = batchRequest.getDocuments().size();
        List<String> accumulatedStorageKeys = new ArrayList<>();
        int successfulRuns = 0;

        for (SingleGenerationRequest singleDoc : batchRequest.getDocuments()) {
            try {
                List<Map<String, Object>> fragments = evaluateAndChunkData(singleDoc.getExtractionPayload());
                byte[] finalDocBytes;

                if (fragments.size() == 1) {
                    finalDocBytes = renderEngine.executeRemoteRender(singleDoc.getStructuralTemplateId(), fragments.get(0));
                } else {
                    List<byte[]> structuralParts = new ArrayList<>();
                    for (Map<String, Object> partition : fragments) {
                        structuralParts.add(renderEngine.executeRemoteRender(singleDoc.getStructuralTemplateId(), partition));
                    }
                    finalDocBytes = modifierService.mergeBinaryFragments(structuralParts);
                }

                byte[] signedBytes = modifierService.applyCryptographicSignature(finalDocBytes);
                String itemKey = "batches/" + batchRequest.getBatchId() + "/" + singleDoc.getDocumentId() + ".pdf";

                storageService.commitRawObject(itemKey, signedBytes);
                accumulatedStorageKeys.add(itemKey);
                successfulRuns++;

                redisTemplate.opsForValue().set(REDIS_STATUS_PREFIX + uniqueJobId, successfulRuns + "/" + maximumCount);
            } catch (Exception e) {
                log.error("Skipping failed entry {} inside batch processing {}", singleDoc.getDocumentId(), uniqueJobId, e);
            }
        }

        try {
            String consolidatedZipKey = "downloadable-bundles/batch-" + batchRequest.getBatchId() + "-" + UUID.randomUUID() + ".zip";
            String secureArchiveUrl = storageService.packageObjectsToZipArchive(consolidatedZipKey, accumulatedStorageKeys);

            redisTemplate.opsForValue().set(REDIS_URL_PREFIX + uniqueJobId, secureArchiveUrl);
            redisTemplate.opsForValue().set(REDIS_STATUS_PREFIX + uniqueJobId, "COMPLETED");
        } catch (Exception e) {
            log.error("Failed to generate final compressed ZIP for batch tracking ID {}", uniqueJobId, e);
            redisTemplate.opsForValue().set(REDIS_STATUS_PREFIX + uniqueJobId, "FAILED");
        }
    }

    public JobStatusResponse fetchJobProgress(String trackingId) {
        String computationalProgress = redisTemplate.opsForValue().get(REDIS_STATUS_PREFIX + trackingId);
        String downloadUrl = redisTemplate.opsForValue().get(REDIS_URL_PREFIX + trackingId);

        if (computationalProgress == null) {
            return new JobStatusResponse(trackingId, "UNKNOWN_ID", null, 0);
        }
        if ("COMPLETED".equals(computationalProgress)) {
            return new JobStatusResponse(trackingId, "COMPLETED", downloadUrl, 100);
        }
        if ("FAILED".equals(computationalProgress)) {
            return new JobStatusResponse(trackingId, "FAILED", null, 0);
        }

        String[] segmentation = computationalProgress.split("/");
        int processed = Integer.parseInt(segmentation[0]);
        int total = Integer.parseInt(segmentation[1]);
        int percentage = (int) (((double) processed / total) * 100);

        return new JobStatusResponse(trackingId, "PROCESSING", null, percentage);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evaluateAndChunkData(Map<String, Object> complexPayload) {
        List<Map<String, Object>> executionChunks = new ArrayList<>();
        if (!complexPayload.containsKey("lineItems")) {
            executionChunks.add(complexPayload);
            return executionChunks;
        }

        List<Object> explicitLines = (List<Object>) complexPayload.get("lineItems");
        if (explicitLines.size() <= CHUNK_THRESHOLD) {
            executionChunks.add(complexPayload);
            return executionChunks;
        }

        for (int index = 0; index < explicitLines.size(); index += CHUNK_THRESHOLD) {
            int targetEndIdx = Math.min(index + CHUNK_THRESHOLD, explicitLines.size());
            List<Object> itemSlice = explicitLines.subList(index, targetEndIdx);

            java.util.HashMap<String, Object> slicedPayload = new java.util.HashMap<>(complexPayload);
            slicedPayload.put("lineItems", itemSlice);
            slicedPayload.put("partitionMetadata", Map.of("offsetIndex", index, "isFinalSegment", targetEndIdx == explicitLines.size()));

            executionChunks.add(slicedPayload);
        }
        return executionChunks;
    }
}