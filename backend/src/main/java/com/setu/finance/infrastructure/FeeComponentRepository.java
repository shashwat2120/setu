package com.setu.finance.infrastructure;

import com.setu.finance.domain.FeeComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeeComponentRepository extends JpaRepository<FeeComponent, UUID> {

    List<FeeComponent> findByFeeStructureId(UUID feeStructureId);
}
