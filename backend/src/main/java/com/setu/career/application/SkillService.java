package com.setu.career.application;

import com.setu.career.domain.Skill;
import com.setu.career.infrastructure.SkillInsertGuard;
import com.setu.career.infrastructure.SkillRepository;
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

/** Day 12 §Career: skills — placement-officer/admin managed (MANAGE_CAREER_LISTINGS); readable by anyone with VIEW_CAREER_LISTINGS (students browsing the catalog to build their skill profile). */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class SkillService {

    private static final String MANAGE_PERMISSION = "MANAGE_CAREER_LISTINGS";
    private static final String VIEW_PERMISSION = "VIEW_CAREER_LISTINGS";

    private final SkillRepository skillRepository;
    private final SkillInsertGuard skillInsertGuard;
    private final SkillCategoryService skillCategoryService;
    private final CollegeService collegeService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public SkillService(SkillRepository skillRepository, SkillInsertGuard skillInsertGuard, SkillCategoryService skillCategoryService,
                         CollegeService collegeService, AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.skillRepository = skillRepository;
        this.skillInsertGuard = skillInsertGuard;
        this.skillCategoryService = skillCategoryService;
        this.collegeService = collegeService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID skillId) implements CreateResult {
        }

        record TenantNotFound() implements CreateResult {
        }

        record CategoryNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {}
        record NotFound() implements UpdateResult {}
        record CategoryNotFound() implements UpdateResult {}
        record InvalidData(String message) implements UpdateResult {}
        record CodeAlreadyInUse() implements UpdateResult {}
        record VersionConflict() implements UpdateResult {}
    }

    @Transactional
    public UpdateResult update(UUID skillId, String code, String name, UUID categoryId, int expectedVersion) {
        Optional<Skill> found = skillRepository.findById(skillId);
        if (found.isEmpty()) return new UpdateResult.NotFound();
        Skill skill = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill", skill.id(), skill.tenantId()));
        if (skill.version() != expectedVersion) return new UpdateResult.VersionConflict();
        if (categoryId != null) {
            Optional<com.setu.career.domain.SkillCategory> category = skillCategoryService.findById(categoryId);
            if (category.isEmpty() || !category.get().tenantId().equals(skill.tenantId())) return new UpdateResult.CategoryNotFound();
        }
        if (skillRepository.existsByTenantIdAndCodeIgnoreCaseAndIdNot(skill.tenantId(), code == null ? "" : code.trim(), skillId)) {
            return new UpdateResult.CodeAlreadyInUse();
        }
        try { skill.update(code, name, categoryId); }
        catch (IllegalArgumentException e) { return new UpdateResult.InvalidData(e.getMessage()); }
        skillRepository.flush();
        auditLogWriter.write(skill.tenantId(), authorizationContext.currentUserId(), "SKILL_UPDATED", "career.skills", skill.id(), Map.of("code", skill.code()));
        return new UpdateResult.Success();
    }

    @Transactional
    public CreateResult create(UUID tenantId, String code, String name, UUID categoryId) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill", UUID.randomUUID(), tenantId));
        if (!collegeService.existsById(tenantId)) {
            return new CreateResult.TenantNotFound();
        }
        if (categoryId != null && skillCategoryService.findById(categoryId).isEmpty()) {
            return new CreateResult.CategoryNotFound();
        }
        Skill skill;
        try {
            skill = new Skill(UUID.randomUUID(), tenantId, code, name, categoryId);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        try {
            skillInsertGuard.insert(skill);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "SKILL_CREATED", "career.skills", skill.id(), Map.of("code", code));
        return new CreateResult.Success(skill.id());
    }

    Optional<Skill> findById(UUID skillId) {
        return skillRepository.findById(skillId);
    }

    @Transactional(readOnly = true)
    public Optional<Skill> get(UUID skillId) {
        Optional<Skill> skill = skillRepository.findById(skillId);
        skill.ifPresent(s -> authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("skill", s.id(), s.tenantId())));
        return skill;
    }

    /** Day 16 §placement: same MANAGE-implies-VIEW gap as JobService — see its javadoc. */
    @Transactional(readOnly = true)
    public List<Skill> listForTenant(UUID tenantId) {
        if (!authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("skill", UUID.randomUUID(), tenantId)).isAllowed()) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("skill", UUID.randomUUID(), tenantId));
        }
        return skillRepository.findByTenantId(tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, String code, String name, UUID categoryId) {
        return switch (create(tenantId, code, name, categoryId)) {
            case CreateResult.Success s -> s.skillId();
            default -> throw new IllegalStateException("seed skill creation failed: " + code);
        };
    }

}
