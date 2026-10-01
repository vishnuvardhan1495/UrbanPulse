package com.urbanpulse.dto;

import com.urbanpulse.entity.ComplaintStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStatusRequest {

    @NotNull(message = "Status is required")
    private ComplaintStatus status;

    private String remarks;
}
