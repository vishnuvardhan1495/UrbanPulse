package com.urbanpulse.dto;

import com.urbanpulse.entity.IssueType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckDuplicateRequest {

    @NotNull(message = "Issue type is required")
    private IssueType issueType;

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    private String imageUrl;

    /**
     * Search radius in meters. Defaults to 30.0 meters if not specified.
     */
    private Double radiusMeters;
}
