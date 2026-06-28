package com.erp.infrastructure.service;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Service
public class DocumentModifierService {

    public byte[] mergeBinaryFragments(List<byte[]> pdfFragments) {
        try (ByteArrayOutputStream combinedStream = new ByteArrayOutputStream()) {
            PDFMergerUtility structuralMerger = new PDFMergerUtility();
            structuralMerger.setDestinationStream(combinedStream);

            for (byte[] fragment : pdfFragments) {
                structuralMerger.addSource(new RandomAccessReadBuffer(new ByteArrayInputStream(fragment)));
            }

            structuralMerger.mergeDocuments(null);
            return combinedStream.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("Structural error during multi-part PDF merge process", ex);
        }
    }

    public byte[] applyCryptographicSignature(byte[] sourceDocument) {
        // Production implementation applies an invisible digital signature using Apache PDFBox 3.x
        // Loads corporate X.509 keys securely out of localized AWS Secrets Manager.
        // Stripped back here to prevent excessive boilerplate.
        return sourceDocument;
    }
}
