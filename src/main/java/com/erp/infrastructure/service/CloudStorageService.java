package com.erp.infrastructure.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class CloudStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private static final String TARGET_BUCKET_NAME = "corporate-erp-pdf-repository";

    public void commitRawObject(String storageKey, byte[] dataBinary) {
        PutObjectRequest objectDefinition = PutObjectRequest.builder()
                .bucket(TARGET_BUCKET_NAME)
                .key(storageKey)
                .contentType("application/pdf")
                .build();
        s3Client.putObject(objectDefinition, RequestBody.fromBytes(dataBinary));
    }

    public String commitToStorageAndPresign(String storageKey, byte[] dataBinary) {
        commitRawObject(storageKey, dataBinary);

        GetObjectRequest fetchMapping = GetObjectRequest.builder()
                .bucket(TARGET_BUCKET_NAME)
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignConfiguration = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofDays(7)) // Retains persistence accessibility
                .getObjectRequest(fetchMapping)
                .build();

        return s3Presigner.presignGetObject(presignConfiguration).url().toString();
    }

    public String packageObjectsToZipArchive(String zipStorageKey, List<String> sourceObjectKeys) throws Exception {
        try (ByteArrayOutputStream zipByteStream = new ByteArrayOutputStream();
             ZipOutputStream compressedStream = new ZipOutputStream(zipByteStream)) {

            for (String documentKey : sourceObjectKeys) {
                GetObjectRequest documentRetrieval = GetObjectRequest.builder()
                        .bucket(TARGET_BUCKET_NAME)
                        .key(documentKey)
                        .build();

                byte[] documentBytes = s3Client.getObjectAsBytes(documentRetrieval).asByteArray();

                String individualFilename = documentKey.substring(documentKey.lastIndexOf('/') + 1);
                ZipEntry entryHeader = new ZipEntry(individualFilename);
                compressedStream.putNextEntry(entryHeader);
                compressedStream.write(documentBytes);
                compressedStream.closeEntry();
            }

            compressedStream.finish();
            byte[] completeZipBinary = zipByteStream.toByteArray();

            PutObjectRequest zipRequestSpecification = PutObjectRequest.builder()
                    .bucket(TARGET_BUCKET_NAME)
                    .key(zipStorageKey)
                    .contentType("application/zip")
                    .build();

            s3Client.putObject(zipRequestSpecification, RequestBody.fromBytes(completeZipBinary));

            GetObjectRequest finalDownloadFetch = GetObjectRequest.builder()
                    .bucket(TARGET_BUCKET_NAME)
                    .key(zipStorageKey)
                    .build();

            return s3Presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofHours(24))
                            .getObjectRequest(finalDownloadFetch)
                            .build()
            ).url().toString();
        }
    }
}
