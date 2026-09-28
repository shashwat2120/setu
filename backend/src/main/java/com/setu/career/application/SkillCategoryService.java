package com.setu.career.application;

import com.setu.career.domain.SkillCategory;
import com.setu.career.infrastructure.SkillCategoryInsertGuard;
import com.setu.career.infrastructure.SkillCategoryRepository;
import com.setu.institution.application.CollegeService;
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

/** Day 12 §Career: skill_categories — placement-officer/admin managed (MANAGE_CAREER_LISTINGS). */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class SkillCategoryService {

    private static final String MANAGE_PERMISSION = "MANAGE_CAREER_LISTINGS";
    private static final String VIEW_PERMISSION = "VIEW_CAREER_LISTINGS";

    private final SkillCategoryRepository skillCategoryRepository;
    private final SkillCategoryInsertGuard skillCategoryInsertGuard;
    private final CollegeService collegeService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public SkillCategoryService(SkillCategoryRepository skillCategoryRepository, SkillCategoryInsertGuard skillCategoryInsertGuard,
                                 CollegeService collegeService, AuthorizationContext authorizationContext,
                                 AuditLogWriter auditLogWriter) {
        this.skillCategoryRepository = skillCategoryRepository;
        this.skillCategoryInsertGuard = skillCategoryInsertGuard;
        this.collegeService = collegeService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID categoryId) implements CreateResult {
        }

        record TenantNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {}
        record NotFound() implements UpdateResult {}
        record InvalidData(String message) implements UpdateResult {}
        record CodeAlreadyInUse() implements UpdateResult {}
        record VersionConflict() implements UpdateResult {}
    }

    @Transactional
    public UpdateResult update(UUID categoryId, String code, String name, int expectedVersion) {
        Optional<SkillCategory> found = skillCategoryRepository.findById(categoryId);
        if (found.isEmpty()) return new UpdateResult.NotFound();
        SkillCategory category = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill_category", category.id(), category.tenantId()));
        if (category.version() != expectedVersion) return new UpdateResult.VersionConflict();
        if (skillCategoryRepository.existsByTenantIdAndCodeIgnoreCaseAndIdNot(category.tenantId(), code == null ? "" : code.trim(), categoryId)) {
            return new UpdateResult.CodeAlreadyInUse();
        }
        try { category.update(code, name); }
        catch (IllegalArgumentException e) { return new UpdateResult.InvalidData(e.getMessage()); }
        skillCategoryRepository.flush();
        auditLogWriter.write(category.tenantId(), authorizationContext.currentUserId(), "SKILL_CATEGORY_UPDATED", "career.skill_categories", category.id(), Map.of("code", category.code()));
        return new UpdateResult.Success();
    }

    @Transactional
    public CreateResult create(UUID tenantId, String code, String name) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill_category", UUID.randomUUID(), tenantId));
        if (!collegeService.existsById(tenantId)) {
            return new CreateResult.TenantNotFound();
        }
        SkillCategory category;
        try {
            category = new SkillCategory(UUID.randomUUID(), tenantId, code, name);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        try {
            skillCategoryInsertGuard.insert(category);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "SKILL_CATEGORY_CREATED",
            "career.skill_categories", category.id(), Map.of("code", code));
        return new CreateResult.Success(category.id());
    }

    Optional<SkillCategory> findById(UUID categoryId) {
        return skillCategoryRepository.findById(categoryId);
    }

    @Transactional(readOnly = true)
    public Optional<SkillCategory> get(UUID categoryId) {
        Optional<SkillCategory> category = skillCategoryRepository.findById(categoryId);
        category.ifPresent(c -> {
            if (!authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("skill_category", c.id(), c.tenantId())).isAllowed()) {
                authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill_category", c.id(), c.tenantId()));
            }
        });
        return category;
    }

    /** Day 16 §placement: same MANAGE-implies-VIEW gap as JobService — see its javadoc. */
    @Transactional(readOnly = true)
    public List<SkillCategory> listForTenant(UUID tenantId) {
        if (!authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("skill_category", UUID.randomUUID(), tenantId)).isAllowed()) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill_category", UUID.randomUUID(), tenantId));
        }
        return skillCategoryRepository.findByTenantId(tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, String code, String name) {
        return switch (create(tenantId, code, name)) {
            case CreateResult.Success s -> s.categoryId();
            default -> throw new IllegalStateException("seed skill category creation failed: " + code);
        };
    }

}
