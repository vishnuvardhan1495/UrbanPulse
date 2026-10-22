package com.urbanpulse.repository;

import com.urbanpulse.entity.ComplaintParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintParticipantRepository extends JpaRepository<ComplaintParticipant, Long> {

    boolean existsByComplaintIdAndCitizenMobileNumber(Long complaintId, String citizenMobileNumber);

    long countByComplaintId(Long complaintId);

    List<ComplaintParticipant> findByComplaintId(Long complaintId);

    List<ComplaintParticipant> findByCitizenMobileNumberOrderByJoinedAtDesc(String citizenMobileNumber);

    Optional<ComplaintParticipant> findByComplaintIdAndCitizenMobileNumber(Long complaintId, String citizenMobileNumber);

    @Query("SELECT cp.complaint.id, COUNT(cp) FROM ComplaintParticipant cp WHERE cp.complaint.id IN :complaintIds GROUP BY cp.complaint.id")
    List<Object[]> countParticipantsByComplaintIds(@Param("complaintIds") List<Long> complaintIds);

    @Query("SELECT cp.complaint.id FROM ComplaintParticipant cp WHERE cp.citizen.mobileNumber = :mobileNumber")
    List<Long> findComplaintIdsByCitizenMobileNumber(@Param("mobileNumber") String mobileNumber);
}
