package com.urbanpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinComplaintResponse {

    private Long complaintId;
    private String complaintNumber;
    private String message;
    private Long joinCount;
    private boolean alreadyJoined;
    private boolean isOriginalReporter;
    private LocalDateTime joinedAt;
}
