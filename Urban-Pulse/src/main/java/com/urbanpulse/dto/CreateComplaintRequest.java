package com.urbanpulse.dto;

import com.urbanpulse.entity.IssueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateComplaintRequest {

    @NotBlank(message = "Image URL is required")
    private String imageUrl;

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    private IssueType issueType;

    private String severity;
}
