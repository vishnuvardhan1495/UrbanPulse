package com.urbanpulse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DTO representing the response from the 8-class ML image classification service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlPredictionResponse {

    private String predictedClass;
    private Double confidence;
    private Object probabilities;
    private Map<String, Double> classProbabilities;
    private String modelVersion;
    private String error;
    private Boolean success;

    // Deprecated legacy fields retained for backward-compatible serialization
    @Deprecated
    private Boolean isCivicIssue;
    @Deprecated
    private String binaryPrediction;
    @Deprecated
    private Double notCivicProbability;
    @Deprecated
    private Double civicProbability;
    @Deprecated
    private String classificationStatus;
    @Deprecated
    private String stage;

    @SuppressWarnings("unchecked")
    public List<Double> getProbabilitiesAsList() {
        if (probabilities instanceof List<?>) {
            return (List<Double>) probabilities;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Double> getProbabilitiesAsMap() {
        if (classProbabilities != null) {
            return classProbabilities;
        }
        if (probabilities instanceof Map<?, ?>) {
            return (Map<String, Double>) probabilities;
        }
        return Collections.emptyMap();
    }
}
