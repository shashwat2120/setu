package com.setu.career.infrastructure;

import com.setu.career.domain.Application;
import com.setu.career.domain.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    List<Application> findByStudentId(UUID studentId);

    List<Application> findByJobId(UUID jobId);

    Optional<Application> findByJobIdAndStudentId(UUID jobId, UUID studentId);

    /** Status-checked conditional UPDATE — see StudentChargeRepository#transitionIfStatus's javadoc for why this replaces a @Version column here; guards against two concurrent lifecycle-transition attempts on the same application. */
    @Modifying
    @Query("update Application a set a.status = :target where a.id = :id and a.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") ApplicationStatus expected, @Param("target") ApplicationStatus target);
}
