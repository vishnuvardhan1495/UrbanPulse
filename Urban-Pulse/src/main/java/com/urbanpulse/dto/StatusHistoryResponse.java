package com.urbanpulse.dto;

import com.urbanpulse.entity.ComplaintStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusHistoryResponse {

    private Long id;
    private ComplaintStatus oldStatus;
    private ComplaintStatus newStatus;
    private String changedByMobile;
    private String changedByName;
    private LocalDateTime changedAt;
    private String remarks;
}
