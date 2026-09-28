package com.setu.career.infrastructure;

import com.setu.career.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByTenantId(UUID tenantId);

    /** Status-checked conditional UPDATE — see StudentChargeRepository#transitionIfStatus's javadoc for why this replaces a @Version column here (no unique-constraint risk on jobs itself, so no insert guard is needed for creation, only this transition guard). */
    @Modifying
    @Query("update Job j set j.status = com.setu.career.domain.JobStatus.CLOSED, j.version = j.version + 1 where j.id = :id and j.status = com.setu.career.domain.JobStatus.OPEN")
    int closeIfOpen(@Param("id") UUID id);
}
