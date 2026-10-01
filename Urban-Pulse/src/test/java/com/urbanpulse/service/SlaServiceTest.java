package com.urbanpulse.service;

import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.IssueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SlaServiceTest {

    private SlaService slaService;

    @BeforeEach
    void setUp() {
        slaService = new SlaService();
    }

    @Test
    @DisplayName("TEST 1: POTHOLE -> 24 hours SLA")
    void testPotholeSlaHours() {
        assertEquals(24, slaService.getSlaHours(IssueType.POTHOLE));
    }

    @Test
    @DisplayName("TEST 2: GARBAGE -> 12 hours SLA")
    void testGarbageSlaHours() {
        assertEquals(12, slaService.getSlaHours(IssueType.GARBAGE));
    }

    @Test
    @DisplayName("TEST 3: WATER_LEAK -> 6 hours SLA")
    void testWaterLeakSlaHours() {
        assertEquals(6, slaService.getSlaHours(IssueType.WATER_LEAK));
    }

    @Test
    @DisplayName("Additional SLAs: DRAINAGE_ISSUE 12h, STREET_LIGHT 24h, INFRASTRUCTURE 48h, NONE 24h")
    void testOtherSlaHours() {
        assertEquals(12, slaService.getSlaHours(IssueType.DRAINAGE_ISSUE));
        assertEquals(24, slaService.getSlaHours(IssueType.STREET_LIGHT_DAMAGE));
        assertEquals(48, slaService.getSlaHours(IssueType.INFRASTRUCTURE_DAMAGE));
        assertEquals(24, slaService.getSlaHours(IssueType.NONE));
    }

    @Test
    @DisplayName("TEST 4: Deadline calculated correctly (createdAt + slaHours)")
    void testDeadlineCalculatedCorrectly() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 10, 10, 0);
        LocalDateTime expectedDeadline = LocalDateTime.of(2026, 9, 11, 10, 0);

        LocalDateTime calculated = slaService.calculateDeadline(createdAt, IssueType.POTHOLE);
        assertEquals(expectedDeadline, calculated);
    }

    @Test
    @DisplayName("TEST 5: Status changes do not reset deadline (deadline remains based on original creation time)")
    void testStatusChangesDoNotResetDeadline() {
        LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 9, 10, 10, 0);
        LocalDateTime originalDeadline = slaService.calculateDeadline(originalCreatedAt, IssueType.WATER_LEAK);

        Complaint complaint = Complaint.builder()
                .createdAt(originalCreatedAt)
                .slaHours(6)
                .slaDeadline(originalDeadline)
                .status(ComplaintStatus.SUBMITTED)
                .build();

        // Simulate status progression SUBMITTED -> FORWARDED -> ASSIGNED -> IN_PROGRESS
        complaint.setStatus(ComplaintStatus.FORWARDED);
        assertEquals(originalDeadline, complaint.getSlaDeadline(), "FORWARDED status must not alter original deadline");

        complaint.setStatus(ComplaintStatus.ASSIGNED);
        assertEquals(originalDeadline, complaint.getSlaDeadline(), "ASSIGNED status must not alter original deadline");

        complaint.setStatus(ComplaintStatus.IN_PROGRESS);
        assertEquals(originalDeadline, complaint.getSlaDeadline(), "IN_PROGRESS status must not alter original deadline");
    }

    @Test
    @DisplayName("TEST 6: Before deadline (far away) -> ON_TIME")
    void testSlaStatus_OnTime() {
        LocalDateTime now = LocalDateTime.now();
        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.IN_PROGRESS)
                .issueType(IssueType.POTHOLE)
                .slaHours(24)
                .slaDeadline(now.plusHours(20)) // 20 hours remaining (> 20% of 24h)
                .build();

        assertEquals("ON_TIME", slaService.getSlaStatus(complaint));
    }

    @Test
    @DisplayName("TEST 7: Near deadline (<= 20% remaining) -> DUE_SOON")
    void testSlaStatus_DueSoon() {
        LocalDateTime now = LocalDateTime.now();
        // 20% of 24 hours = 4.8 hours = 288 minutes
        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.IN_PROGRESS)
                .issueType(IssueType.POTHOLE)
                .slaHours(24)
                .slaDeadline(now.plusMinutes(120)) // 2 hours remaining (<= 4.8h)
                .build();

        assertEquals("DUE_SOON", slaService.getSlaStatus(complaint));
    }

    @Test
    @DisplayName("TEST 8: After deadline -> OVERDUE")
    void testSlaStatus_Overdue() {
        LocalDateTime now = LocalDateTime.now();
        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.IN_PROGRESS)
                .issueType(IssueType.POTHOLE)
                .slaHours(24)
                .slaDeadline(now.minusHours(2)) // 2 hours in the past
                .build();

        assertEquals("OVERDUE", slaService.getSlaStatus(complaint));
    }

    @Test
    @DisplayName("TEST 9: Resolved before deadline -> resolvedWithinSla = true")
    void testResolvedWithinSla_True() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 11, 10, 0);
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 11, 9, 30);

        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.RESOLVED)
                .slaDeadline(deadline)
                .resolvedAt(resolvedAt)
                .build();

        assertTrue(slaService.isResolvedWithinSla(complaint));
    }

    @Test
    @DisplayName("TEST 10: Resolved after deadline -> resolvedWithinSla = false")
    void testResolvedWithinSla_False() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 11, 10, 0);
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 11, 11, 30);

        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.RESOLVED)
                .slaDeadline(deadline)
                .resolvedAt(resolvedAt)
                .build();

        assertFalse(slaService.isResolvedWithinSla(complaint));
    }

    @Test
    @DisplayName("TEST 11: Resolved complaint -> SLA status RESOLVED")
    void testSlaStatus_Resolved() {
        Complaint complaint = Complaint.builder()
                .status(ComplaintStatus.RESOLVED)
                .slaDeadline(LocalDateTime.now().minusHours(5))
                .resolvedAt(LocalDateTime.now().minusHours(6))
                .build();

        assertEquals("RESOLVED", slaService.getSlaStatus(complaint));
    }
}
