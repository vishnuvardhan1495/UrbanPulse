package com.urbanpulse.dto;

import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.IssueType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintResponse {

    private Long id;
    private String complaintNumber;
    private IssueType issueType;
    private ComplaintStatus status;
    private String severity;
    private String priority;
    private Double latitude;
    private Double longitude;
    private String imageUrl;
    private String proofImageUrl;
    private Double confidenceScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer slaHours;
    private LocalDateTime slaDeadline;
    private String slaStatus;
    private Long remainingMinutes;
    private LocalDateTime resolvedAt;
    private Boolean resolvedWithinSla;

    private String citizenMobile;
    private String citizenName;

    private Long departmentId;
    private String departmentName;

    private String supervisorMobile;
    private String supervisorName;

    private String assignedWorkerMobile;
    private String assignedWorkerName;

    private List<StatusHistoryResponse> statusHistory;
}
