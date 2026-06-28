package com.erp.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class RemoteRenderEngine {

    private final LambdaClient lambdaClient;
    private final ObjectMapper payloadObjectMapper = new ObjectMapper();
    private static final String LAMBDA_FUNCTION_NAME = "arn:aws:lambda:ap-south-1:123456789012:function:puppeteer-pdf-renderer";

    public byte[] executeRemoteRender(String templateId, Map<String, Object> stateVariables) {
        try {
            Map<String, Object> structuralWrapper = Map.of(
                    "templateId", templateId,
                    "renderVariables", stateVariables
            );
            String transitJson = payloadObjectMapper.writeValueAsString(structuralWrapper);

            InvokeRequest invocationRequest = InvokeRequest.builder()
                    .functionName(LAMBDA_FUNCTION_NAME)
                    .payload(SdkBytes.fromUtf8String(transitJson))
                    .build();

            InvokeResponse serverlessExecutionResponse = lambdaClient.invoke(invocationRequest);

            if (serverlessExecutionResponse.functionError() != null) {
                throw new RuntimeException("Serverless Chromium engine failed: " + serverlessExecutionResponse.payload().asUtf8String());
            }

            return serverlessExecutionResponse.payload().asByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to complete remote PDF render phase", e);
        }
    }
}
