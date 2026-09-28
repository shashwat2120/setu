package com.setu.career.infrastructure;

import com.setu.career.domain.PlacementRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlacementRecordRepository extends JpaRepository<PlacementRecord, UUID> {

    List<PlacementRecord> findByStudentId(UUID studentId);

    List<PlacementRecord> findByTenantId(UUID tenantId);
}
