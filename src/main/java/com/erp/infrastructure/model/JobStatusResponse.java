package com.erp.infrastructure.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobStatusResponse {
    private String targetId;
    private String operationalStatus;
    private String resourceDownloadUrl;
    private int completionPercentage;
}
