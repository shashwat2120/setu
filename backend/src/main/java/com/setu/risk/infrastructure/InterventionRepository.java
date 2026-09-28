package com.setu.risk.infrastructure;

import com.setu.risk.domain.Intervention;
import com.setu.risk.domain.InterventionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InterventionRepository extends JpaRepository<Intervention, UUID> {

    List<Intervention> findByStudentId(UUID studentId);

    /** Status-checked conditional UPDATE — same idiom as RecommendationRepository#transitionIfStatus; no {@code @Version} column exists on this table either. */
    @Modifying
    @Query("update Intervention i set i.status = :target where i.id = :id and i.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") InterventionStatus expected, @Param("target") InterventionStatus target);
}
