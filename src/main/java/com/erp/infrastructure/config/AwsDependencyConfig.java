package com.erp.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class AwsDependencyConfig {

    private static final Region TARGET_AWS_REGION = Region.AP_SOUTH_1;

    @Bean
    public S3Client configureSystemS3Client() {
        return S3Client.builder()
                .region(TARGET_AWS_REGION)
                .build();
    }

    @Bean
    public S3Presigner configureSystemS3Presigner() {
        return S3Presigner.builder()
                .region(TARGET_AWS_REGION)
                .build();
    }

    @Bean
    public LambdaClient configureSystemLambdaClient() {
        return LambdaClient.builder()
                .region(TARGET_AWS_REGION)
                .build();
    }
}
