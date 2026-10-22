package com.urbanpulse.dto;

import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.IssueType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DuplicateComplaintSummary {

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
    private Double distanceMeters;
    private Long joinCount;
    private Boolean hasJoined;
    private Boolean isOriginalReporter;
    private Boolean eligibleForJoin;
    private String departmentName;
    private LocalDateTime createdAt;
    private LocalDateTime slaDeadline;
}
