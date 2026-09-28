package com.setu.catalog.application;

import com.setu.catalog.domain.Building;
import com.setu.catalog.domain.Room;
import com.setu.catalog.infrastructure.BuildingRepository;
import com.setu.catalog.infrastructure.RoomInsertGuard;
import com.setu.catalog.infrastructure.RoomRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rooms (Day 7 §11). Building tenant ownership is validated in-module (both
 * entities live in {@code catalog}, no cross-module port needed). No
 * audit/version columns on catalog.rooms itself (V24) — see
 * {@link BuildingService}'s javadoc for the same disclosed characteristic.
 * <p>
 * The invariant "a room must not be assignable to a section whose capacity
 * exceeds the room capacity" (Day 7 §11) is enforced where the assignment
 * actually happens — {@code enrollment.application.CourseSectionService},
 * via {@link #findTenantAndCapacity} — not here, since no section may exist
 * yet at room-creation time for this check to apply to.
 * <p>
 * {@code @NamedInterface} on this class (Day 7 §3 ceremony): caller is
 * {@code enrollment.application.CourseSectionService}, to validate a
 * section's optional room's tenant and capacity before accepting it. Only
 * {@link #findTenantAndCapacity} is exposed — a bare projection, not the
 * {@link Room} entity. No reverse edge from catalog into enrollment exists,
 * so the plain {@code @NamedInterface} pattern applies directly, same as
 * {@code catalog.CourseService}. Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class RoomService {

    private static final String MANAGE_PERMISSION = "MANAGE_FACILITIES";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final RoomRepository roomRepository;
    private final RoomInsertGuard roomInsertGuard;
    private final BuildingRepository buildingRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public RoomService(RoomRepository roomRepository, RoomInsertGuard roomInsertGuard, BuildingRepository buildingRepository,
                        AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.roomRepository = roomRepository;
        this.roomInsertGuard = roomInsertGuard;
        this.buildingRepository = buildingRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID roomId) implements CreateResult {
        }

        record BuildingNotFound() implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }

        record InvalidRoomData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID buildingId, String code, int capacity, String featuresJson) {
        Optional<Building> building = buildingRepository.findById(buildingId);
        if (building.isEmpty()) {
            return new CreateResult.BuildingNotFound();
        }
        UUID tenantId = building.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("room", UUID.randomUUID(), tenantId));

        UUID actor = authorizationContext.currentUserId();
        Room room;
        try {
            room = new Room(UUID.randomUUID(), tenantId, buildingId, code, capacity, featuresJson);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidRoomData(e.getMessage());
        }
        try {
            roomInsertGuard.insert(room);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, actor, "ROOM_CREATED", "catalog.rooms", room.id(),
            Map.of("code", code, "buildingId", buildingId, "capacity", capacity));
        return new CreateResult.Success(room.id());
    }

    @Transactional(readOnly = true)
    public Optional<Room> get(UUID roomId) {
        Optional<Room> room = roomRepository.findById(roomId);
        room.ifPresent(r -> authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("room", r.id(), r.tenantId())));
        return room;
    }

    @Transactional(readOnly = true)
    public List<Room> listForBuilding(UUID buildingId, UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("room", UUID.randomUUID(), tenantId));
        return roomRepository.findByBuildingId(buildingId);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidRoomData(String message) implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID roomId, int capacity, String featuresJson) {
        Optional<Room> maybeRoom = roomRepository.findById(roomId);
        if (maybeRoom.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Room room = maybeRoom.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("room", room.id(), room.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            room.update(capacity, featuresJson);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidRoomData(e.getMessage());
        }
        roomRepository.save(room);
        auditLogWriter.write(room.tenantId(), actor, "ROOM_UPDATED", "catalog.rooms", room.id(),
            Map.of("capacity", capacity));
        return new UpdateResult.Success();
    }

    /** See this class's javadoc for the full Day 7 §3 cross-module justification. */
    public record RoomTenantCapacity(UUID tenantId, int capacity) {
    }

    @Transactional(readOnly = true)
    public Optional<RoomTenantCapacity> findTenantAndCapacity(UUID roomId) {
        return roomRepository.findById(roomId).map(r -> new RoomTenantCapacity(r.tenantId(), r.capacity()));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID buildingId, String code, int capacity, String featuresJson) {
        return switch (create(buildingId, code, capacity, featuresJson)) {
            case CreateResult.Success s -> s.roomId();
            default -> throw new IllegalStateException("seed room creation failed: " + code);
        };
    }

}
