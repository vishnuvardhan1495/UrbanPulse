package com.urbanpulse.service;

import com.urbanpulse.dto.MlPredictionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Service for communicating with the Python Flask ML image classification service.
 * Supports image file classification and health monitoring.
 * Fully isolated: ML inference failures NEVER fail the complaint transaction.
 */
@Service
public class MlInferenceService {

    private static final Logger logger = LoggerFactory.getLogger(MlInferenceService.class);

    private final String mlServiceUrl;
    private final RestTemplate restTemplate;

    public MlInferenceService(
            @Value("${urbanpulse.ml.service-url:http://localhost:5001}") String mlServiceUrl,
            @Value("${urbanpulse.ml.timeout-ms:8000}") int timeoutMs) {
        this.mlServiceUrl = mlServiceUrl.endsWith("/") ? mlServiceUrl.substring(0, mlServiceUrl.length() - 1) : mlServiceUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        logger.info("MlInferenceService initialized with URL: {} and timeout: {}ms", this.mlServiceUrl, timeoutMs);
    }

    /**
     * Sends image bytes to the ML inference service /predict endpoint.
     * Returns Optional<MlPredictionResponse> on success, or Optional.empty() on failure.
     */
    public Optional<MlPredictionResponse> predictImage(byte[] imageBytes, String filename) {
        if (imageBytes == null || imageBytes.length == 0) {
            logger.warn("ML inference skipped: image bytes are null or empty.");
            return Optional.empty();
        }

        String predictUrl = mlServiceUrl + "/predict";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            String safeFilename = (filename != null && !filename.trim().isEmpty()) ? filename : "complaint.jpg";
            ByteArrayResource fileResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return safeFilename;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<MlPredictionResponse> response = restTemplate.postForEntity(
                    predictUrl,
                    requestEntity,
                    MlPredictionResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                MlPredictionResponse prediction = response.getBody();
                logger.info("ML inference success: class='{}', confidence={}",
                        prediction.getPredictedClass(), prediction.getConfidence());
                return Optional.of(prediction);
            } else {
                logger.warn("ML inference non-2xx status: {}", response.getStatusCode());
                return Optional.empty();
            }

        } catch (Exception ex) {
            logger.warn("ML inference service call failed to {}: {}", predictUrl, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks if the ML inference service is reachable and has loaded the model.
     */
    public boolean isHealthy() {
        String healthUrl = mlServiceUrl + "/health";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.debug("ML service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
