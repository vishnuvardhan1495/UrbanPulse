package com.urbanpulse.service;

import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.IssueType;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

@Service
public class SlaService {

    private static final Map<IssueType, Integer> SLA_HOURS_CONFIG;

    static {
        Map<IssueType, Integer> map = new EnumMap<>(IssueType.class);
        map.put(IssueType.POTHOLE, 24);
        map.put(IssueType.GARBAGE, 12);
        map.put(IssueType.WATER_LEAK, 6);
        map.put(IssueType.DRAINAGE_ISSUE, 12);
        map.put(IssueType.STREET_LIGHT_DAMAGE, 24);
        map.put(IssueType.INFRASTRUCTURE_DAMAGE, 48);
        map.put(IssueType.NONE, 24);
        SLA_HOURS_CONFIG = Collections.unmodifiableMap(map);
    }

    public int getSlaHours(IssueType issueType) {
        if (issueType == null) {
            return 24;
        }
        return SLA_HOURS_CONFIG.getOrDefault(issueType, 24);
    }

    public LocalDateTime calculateDeadline(LocalDateTime createdAt, IssueType issueType) {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        int hours = getSlaHours(issueType);
        return createdAt.plusHours(hours);
    }

    public String getSlaStatus(Complaint complaint) {
        if (complaint == null) {
            return "ON_TIME";
        }

        if (complaint.getStatus() == ComplaintStatus.RESOLVED) {
            return "RESOLVED";
        }

        LocalDateTime deadline = complaint.getSlaDeadline();
        if (deadline == null) {
            return "ON_TIME";
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(deadline)) {
            return "OVERDUE";
        }

        int slaHours = complaint.getSlaHours() != null ? complaint.getSlaHours() : getSlaHours(complaint.getIssueType());
        long totalMinutes = (long) slaHours * 60L;
        long remainingMinutes = ChronoUnit.MINUTES.between(now, deadline);

        // Due soon threshold is 20% of total duration
        long dueSoonThresholdMinutes = (long) (totalMinutes * 0.20);
        if (remainingMinutes <= dueSoonThresholdMinutes) {
            return "DUE_SOON";
        }

        return "ON_TIME";
    }

    public Long getRemainingMinutes(Complaint complaint) {
        if (complaint == null || complaint.getSlaDeadline() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return ChronoUnit.MINUTES.between(now, complaint.getSlaDeadline());
    }

    public Boolean isResolvedWithinSla(Complaint complaint) {
        if (complaint == null || complaint.getResolvedAt() == null || complaint.getSlaDeadline() == null) {
            return null;
        }
        return !complaint.getResolvedAt().isAfter(complaint.getSlaDeadline());
    }
}
