package com.erp.infrastructure.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkGenerationRequest {
    private String batchId;
    private List<SingleGenerationRequest> documents;
}