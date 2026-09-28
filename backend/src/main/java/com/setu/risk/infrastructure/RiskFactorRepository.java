package com.setu.risk.infrastructure;

import com.setu.risk.domain.RiskFactor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RiskFactorRepository extends JpaRepository<RiskFactor, UUID> {

    List<RiskFactor> findByRiskScoreIdOrderByContributionDesc(UUID riskScoreId);
}
