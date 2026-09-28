package com.setu.risk.infrastructure;

import com.setu.risk.domain.InterventionAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterventionActionRepository extends JpaRepository<InterventionAction, UUID> {

    List<InterventionAction> findByInterventionIdOrderByCreatedAtDesc(UUID interventionId);

    long countByInterventionId(UUID interventionId);
}
