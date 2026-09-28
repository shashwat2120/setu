package com.setu.risk.infrastructure;

import com.setu.risk.domain.Recommendation;
import com.setu.risk.domain.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

    List<Recommendation> findByRiskScoreId(UUID riskScoreId);

    /** Status-checked conditional UPDATE — no {@code @Version} column exists on this table (V48 schema), so the expected current status is itself the concurrency guard, same established idiom as StudentChargeRepository#transitionIfStatus (Day 12). */
    @Modifying
    @Query("update Recommendation r set r.status = :target where r.id = :id and r.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") RecommendationStatus expected, @Param("target") RecommendationStatus target);
}
