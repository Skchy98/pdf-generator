package com.erp.infrastructure.controller;

import com.erp.infrastructure.model.BulkGenerationRequest;
import com.erp.infrastructure.model.JobStatusResponse;
import com.erp.infrastructure.model.SingleGenerationRequest;
import com.erp.infrastructure.service.OrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/pdf-engine")
@RequiredArgsConstructor
public class PdfController {

    private final OrchestrationService orchestrationService;

    @PostMapping("/render-single")
    public ResponseEntity<JobStatusResponse> generateSingleDocument(@RequestBody SingleGenerationRequest request) {
        log.info("Received single PDF generation request for DocID: {}", request.getDocumentId());
        String accessUrl = orchestrationService.processSynchronousDocument(request);
        return ResponseEntity.ok(new JobStatusResponse(request.getDocumentId(), "COMPLETED", accessUrl, 100));
    }

    @PostMapping("/render-bulk")
    public ResponseEntity<JobStatusResponse> generateBulkBatch(@RequestBody BulkGenerationRequest request) {
        log.info("Received bulk processing request for BatchID: {}, Item Count: {}", request.getBatchId(), request.getDocuments().size());
        String trackingId = orchestrationService.registerAndSubmitBulkJob(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new JobStatusResponse(trackingId, "QUEUED", null, 0));
    }

    @GetMapping("/job-status/{trackingId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String trackingId) {
        JobStatusResponse status = orchestrationService.fetchJobProgress(trackingId);
        return ResponseEntity.ok(status);
    }
}