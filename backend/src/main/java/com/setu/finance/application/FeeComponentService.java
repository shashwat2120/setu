package com.setu.finance.application;

import com.setu.finance.domain.FeeComponent;
import com.setu.finance.domain.FeeStructure;
import com.setu.finance.infrastructure.FeeComponentInsertGuard;
import com.setu.finance.infrastructure.FeeComponentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Day 12 §Finance: fee_components — one priced line item within a {@link FeeStructure}. Admin-managed only (MANAGE_FINANCE). */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class FeeComponentService {

    private static final String MANAGE_PERMISSION = "MANAGE_FINANCE";

    private final FeeComponentRepository feeComponentRepository;
    private final FeeComponentInsertGuard feeComponentInsertGuard;
    private final FeeStructureService feeStructureService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public FeeComponentService(FeeComponentRepository feeComponentRepository, FeeComponentInsertGuard feeComponentInsertGuard,
                                FeeStructureService feeStructureService, AuthorizationContext authorizationContext,
                                AuditLogWriter auditLogWriter) {
        this.feeComponentRepository = feeComponentRepository;
        this.feeComponentInsertGuard = feeComponentInsertGuard;
        this.feeStructureService = feeStructureService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID componentId) implements CreateResult {
        }

        record FeeStructureNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID feeStructureId, String code, BigDecimal amount, String currency) {
        Optional<FeeStructure> maybeStructure = feeStructureService.get(feeStructureId);
        if (maybeStructure.isEmpty()) {
            return new CreateResult.FeeStructureNotFound();
        }
        FeeStructure structure = maybeStructure.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("fee_component", UUID.randomUUID(), structure.tenantId()));

        FeeComponent component;
        try {
            component = new FeeComponent(UUID.randomUUID(), feeStructureId, code, amount, currency);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        try {
            feeComponentInsertGuard.insert(component);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(structure.tenantId(), authorizationContext.currentUserId(), "FEE_COMPONENT_CREATED",
            "finance.fee_components", component.id(), Map.of("feeStructureId", feeStructureId, "code", code));
        return new CreateResult.Success(component.id());
    }

    /** Package-private, no independent authorization — callers (StudentChargeService) already authorize their own operation against the resolved tenant. */
    Optional<FeeComponent> findById(UUID componentId) {
        return feeComponentRepository.findById(componentId);
    }

    @Transactional(readOnly = true)
    public List<FeeComponent> listForStructure(UUID feeStructureId) {
        if (feeStructureService.get(feeStructureId).isEmpty()) {
            return List.of();
        }
        return feeComponentRepository.findByFeeStructureId(feeStructureId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID feeStructureId, String code, java.math.BigDecimal amount, String currency) {
        return switch (create(feeStructureId, code, amount, currency)) {
            case CreateResult.Success s -> s.componentId();
            default -> throw new IllegalStateException("seed fee component creation failed: " + code);
        };
    }

}
