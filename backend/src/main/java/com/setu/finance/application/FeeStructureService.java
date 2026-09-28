package com.setu.finance.application;

import com.setu.finance.domain.FeeStructure;
import com.setu.finance.infrastructure.FeeStructureRepository;
import com.setu.institution.application.CollegeService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Day 12 §Finance: fee_structures — admin-managed only (MANAGE_FINANCE: SUPER_ADMIN platform-wide, COLLEGE_ADMIN tenant-wide, per V67). Defines what CAN be charged, never a charge itself. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class FeeStructureService {

    private static final String MANAGE_PERMISSION = "MANAGE_FINANCE";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final FeeStructureRepository feeStructureRepository;
    private final CollegeService collegeService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public FeeStructureService(FeeStructureRepository feeStructureRepository, CollegeService collegeService,
                                AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.feeStructureRepository = feeStructureRepository;
        this.collegeService = collegeService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID feeStructureId) implements CreateResult {
        }

        record TenantNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, UUID programId, String name, UUID academicYearId) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("fee_structure", UUID.randomUUID(), tenantId));
        if (!collegeService.existsById(tenantId)) {
            return new CreateResult.TenantNotFound();
        }
        FeeStructure structure;
        try {
            structure = new FeeStructure(UUID.randomUUID(), tenantId, programId, name, academicYearId);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        feeStructureRepository.saveAndFlush(structure);
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "FEE_STRUCTURE_CREATED",
            "finance.fee_structures", structure.id(), Map.of("name", name));
        return new CreateResult.Success(structure.id());
    }

    @Transactional(readOnly = true)
    public Optional<FeeStructure> get(UUID feeStructureId) {
        Optional<FeeStructure> structure = feeStructureRepository.findById(feeStructureId);
        structure.ifPresent(s -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("fee_structure", s.id(), s.tenantId())));
        return structure;
    }

    @Transactional(readOnly = true)
    public List<FeeStructure> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("fee_structure", UUID.randomUUID(), tenantId));
        return feeStructureRepository.findByTenantId(tenantId);
    }

    public enum DeactivateResult {SUCCESS, NOT_FOUND}

    @Transactional
    public DeactivateResult deactivate(UUID feeStructureId) {
        Optional<FeeStructure> maybeStructure = feeStructureRepository.findById(feeStructureId);
        if (maybeStructure.isEmpty()) {
            return DeactivateResult.NOT_FOUND;
        }
        FeeStructure structure = maybeStructure.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("fee_structure", structure.id(), structure.tenantId()));
        structure.setInactive();
        feeStructureRepository.save(structure);
        auditLogWriter.write(structure.tenantId(), authorizationContext.currentUserId(), "FEE_STRUCTURE_DEACTIVATED",
            "finance.fee_structures", structure.id(), Map.of());
        return DeactivateResult.SUCCESS;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, UUID programId, String name, UUID academicYearId) {
        return switch (create(tenantId, programId, name, academicYearId)) {
            case CreateResult.Success s -> s.feeStructureId();
            default -> throw new IllegalStateException("seed fee structure creation failed: " + name);
        };
    }

}
