package com.setu.risk.infrastructure;

import com.setu.risk.domain.RiskScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskScoreRepository extends JpaRepository<RiskScore, UUID> {

    /** "Current" score — the latest calculation, per the append-only design (RiskScore's own javadoc). */
    Optional<RiskScore> findFirstByStudentIdOrderByCalculatedAtDesc(UUID studentId);

    List<RiskScore> findByStudentIdOrderByCalculatedAtDesc(UUID studentId);

    /** Day 16 §college-admin: every score row in a tenant, newest first — the service layer collapses this to one (the latest) row per student for the institution-wide risk view, since RiskScore is append-only and this repository has no per-student "latest" grouping query. */
    List<RiskScore> findByTenantIdOrderByCalculatedAtDesc(UUID tenantId);
}
