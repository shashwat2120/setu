package com.setu.catalog.application;

import com.setu.catalog.domain.Building;
import com.setu.catalog.infrastructure.BuildingInsertGuard;
import com.setu.catalog.infrastructure.BuildingRepository;
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

/** Buildings (Day 7 §10). No audit/version columns on catalog.buildings itself (V24) — governance.audit_logs still records every mutation independently, disclosed rather than assumed. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class BuildingService {

    private static final String MANAGE_PERMISSION = "MANAGE_FACILITIES";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final BuildingRepository buildingRepository;
    private final BuildingInsertGuard buildingInsertGuard;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public BuildingService(BuildingRepository buildingRepository, BuildingInsertGuard buildingInsertGuard,
                            AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.buildingRepository = buildingRepository;
        this.buildingInsertGuard = buildingInsertGuard;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID buildingId) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, String code, String name) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("building", UUID.randomUUID(), tenantId));
        UUID actor = authorizationContext.currentUserId();
        Building building = new Building(UUID.randomUUID(), tenantId, code, name);
        try {
            buildingInsertGuard.insert(building);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, actor, "BUILDING_CREATED", "catalog.buildings", building.id(),
            Map.of("code", code, "name", name));
        return new CreateResult.Success(building.id());
    }

    @Transactional(readOnly = true)
    public Optional<Building> get(UUID buildingId) {
        Optional<Building> building = buildingRepository.findById(buildingId);
        building.ifPresent(b -> authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("building", b.id(), b.tenantId())));
        return building;
    }

    @Transactional(readOnly = true)
    public List<Building> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("building", UUID.randomUUID(), tenantId));
        return buildingRepository.findByTenantId(tenantId);
    }

    public enum UpdateResult {SUCCESS, NOT_FOUND}

    @Transactional
    public UpdateResult update(UUID buildingId, String name) {
        Optional<Building> maybeBuilding = buildingRepository.findById(buildingId);
        if (maybeBuilding.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        Building building = maybeBuilding.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("building", building.id(), building.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        building.update(name);
        buildingRepository.save(building);
        auditLogWriter.write(building.tenantId(), actor, "BUILDING_UPDATED", "catalog.buildings", building.id(),
            Map.of("name", name));
        return UpdateResult.SUCCESS;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, String code, String name) {
        return switch (create(tenantId, code, name)) {
            case CreateResult.Success s -> s.buildingId();
            default -> throw new IllegalStateException("seed building creation failed: " + code);
        };
    }

}
