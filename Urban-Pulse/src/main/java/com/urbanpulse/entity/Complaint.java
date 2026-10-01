package com.urbanpulse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "complaints")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "complaint_number", nullable = false, unique = true)
    private String complaintNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false)
    private IssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ComplaintStatus status;

    @Column(name = "severity")
    private String severity;

    @Column(name = "priority")
    private String priority;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;

    @Column(name = "proof_image_url", length = 1024)
    private String proofImageUrl;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_within_sla")
    private Boolean resolvedWithinSla;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "citizen_id", nullable = false)
    private User citizen;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_worker_id")
    private User assignedWorker;
}
