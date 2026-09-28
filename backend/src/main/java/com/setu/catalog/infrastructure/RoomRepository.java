package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByBuildingIdAndCode(UUID buildingId, String code);

    List<Room> findByBuildingId(UUID buildingId);

    List<Room> findByTenantId(UUID tenantId);
}
