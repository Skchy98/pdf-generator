package com.erp.infrastructure.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SingleGenerationRequest {
    private String documentId;
    private String structuralTemplateId;
    private Map<String, Object> extractionPayload;
}