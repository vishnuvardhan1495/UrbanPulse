package com.urbanpulse.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForwardComplaintRequest {

    @NotNull(message = "Department ID is required")
    private Long departmentId;
}
