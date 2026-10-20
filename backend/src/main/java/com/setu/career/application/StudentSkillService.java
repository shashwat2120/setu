package com.setu.career.application;

import com.setu.career.domain.ProficiencyLevel;
import com.setu.career.domain.Skill;
import com.setu.career.domain.StudentSkill;
import com.setu.career.infrastructure.StudentSkillInsertGuard;
import com.setu.career.infrastructure.StudentSkillRepository;
import com.setu.directory.application.StudentProfileService;
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

/** Day 12 §Career: student_skills — the student's own skill profile, self-service only (MANAGE_OWN_SKILLS). A placement officer/admin never edits a student's skills on their behalf — only the student who owns the profile can. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class StudentSkillService {

    private static final String MANAGE_PERMISSION = "MANAGE_OWN_SKILLS";
    /** What a placement officer holds: it lets them read the skills of the students who apply to their jobs. */
    private static final String MANAGE_LISTINGS_PERMISSION = "MANAGE_CAREER_LISTINGS";
    private static final String VIEW_PERMISSION = "VIEW_CAREER_LISTINGS";

    private final StudentSkillRepository studentSkillRepository;
    private final StudentSkillInsertGuard studentSkillInsertGuard;
    private final SkillService skillService;
    private final StudentProfileService studentProfileService;
    private final CareerAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StudentSkillService(StudentSkillRepository studentSkillRepository, StudentSkillInsertGuard studentSkillInsertGuard,
                                SkillService skillService, StudentProfileService studentProfileService,
                                CareerAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                AuditLogWriter auditLogWriter) {
        this.studentSkillRepository = studentSkillRepository;
        this.studentSkillInsertGuard = studentSkillInsertGuard;
        this.skillService = skillService;
        this.studentProfileService = studentProfileService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AddResult {
        record Success(UUID studentSkillId) implements AddResult {
        }

        record StudentNotFound() implements AddResult {
        }

        record SkillNotFound() implements AddResult {
        }

        record AlreadyAdded() implements AddResult {
        }
    }

    @Transactional
    public AddResult add(UUID studentId, UUID skillId, ProficiencyLevel proficiencyLevel, String evidenceRef) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new AddResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        assertOwnStudent(tenantId, studentId);

        Optional<Skill> skill = skillService.findById(skillId);
        if (skill.isEmpty() || !skill.get().tenantId().equals(tenantId)) {
            return new AddResult.SkillNotFound();
        }

        StudentSkill studentSkill = new StudentSkill(UUID.randomUUID(), tenantId, studentId, skillId, proficiencyLevel, evidenceRef);
        try {
            studentSkillInsertGuard.insert(studentSkill);
        } catch (DataIntegrityViolationException e) {
            return new AddResult.AlreadyAdded();
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "STUDENT_SKILL_ADDED",
            "career.student_skills", studentSkill.id(), Map.of("studentId", studentId, "skillId", skillId));
        return new AddResult.Success(studentSkill.id());
    }

    public enum RemoveResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RemoveResult remove(UUID studentSkillId) {
        Optional<StudentSkill> maybeSkill = studentSkillRepository.findById(studentSkillId);
        if (maybeSkill.isEmpty()) {
            return RemoveResult.NOT_FOUND;
        }
        StudentSkill studentSkill = maybeSkill.get();
        assertOwnStudent(studentSkill.tenantId(), studentSkill.studentId());
        studentSkillRepository.delete(studentSkill);
        auditLogWriter.write(studentSkill.tenantId(), authorizationContext.currentUserId(), "STUDENT_SKILL_REMOVED",
            "career.student_skills", studentSkillId, Map.of("studentId", studentSkill.studentId()));
        return RemoveResult.SUCCESS;
    }

    /** Own (student) or a career-management/read permission holder. */
    @Transactional(readOnly = true)
    public List<StudentSkill> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        UUID tenantId = studentInfo.get().tenantId();
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isPresent() && !ownStudentId.get().equals(studentId)) {
            // a student reads their own profile and no one else's, whatever else their role may let them view
            throw new org.springframework.security.access.AccessDeniedException("permission_denied");
        }
        if (ownStudentId.isEmpty()) {
            // a placement officer manages the jobs and shortlists their applicants, so they must be able to read the skills those decisions rest on: managing implies viewing
            var resource = ResourceRef.of("student_skill", studentId, tenantId);
            if (!authorizationContext.can(VIEW_PERMISSION, resource).isAllowed()) {
                authorizationContext.assertCan(MANAGE_LISTINGS_PERMISSION, resource);
            }
        }
        return studentSkillRepository.findByStudentId(studentId);
    }

    private void assertOwnStudent(UUID tenantId, UUID studentId) {
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        boolean isOwn = ownStudentId.isPresent() && ownStudentId.get().equals(studentId);
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("student_skill", studentId, tenantId).ownedBy(isOwn ? authorizationContext.currentUserId() : UUID.randomUUID()));
    }
}
