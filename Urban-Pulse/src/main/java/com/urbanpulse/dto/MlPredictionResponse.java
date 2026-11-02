package com.urbanpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlPredictionResponse {
    private String predictedClass;
    private Double confidence;
    private Map<String, Double> probabilities;
    private String modelVersion;
    private String error;
}
