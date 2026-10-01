package com.urbanpulse.repository;

import com.urbanpulse.entity.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findByCitizenMobileNumberOrderByCreatedAtDesc(String citizenMobileNumber);

    List<Complaint> findByDepartmentIdOrderByCreatedAtDesc(Long departmentId);

    List<Complaint> findByAssignedWorkerMobileNumberOrderByCreatedAtDesc(String workerMobileNumber);

    List<Complaint> findAllByOrderByCreatedAtDesc();

    Optional<Complaint> findByComplaintNumber(String complaintNumber);

    boolean existsByComplaintNumber(String complaintNumber);
}
