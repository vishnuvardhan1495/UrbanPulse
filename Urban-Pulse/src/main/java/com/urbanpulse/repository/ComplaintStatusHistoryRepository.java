package com.urbanpulse.repository;

import com.urbanpulse.entity.ComplaintStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplaintStatusHistoryRepository extends JpaRepository<ComplaintStatusHistory, Long> {

    List<ComplaintStatusHistory> findByComplaintIdOrderByChangedAtAsc(Long complaintId);
}
