package com.setu.career.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.career.domain.ResumeContent;
import com.setu.career.domain.StudentResume;
import com.setu.career.infrastructure.StudentResumeRepository;
import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.career.StudentStandings;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A student's resume: what they say about themselves (a headline, a summary, links, projects, certifications, experience), kept with the facts the system itself holds about
 * them (their CGPA, attendance, graduating batch, program and skills), which a resume cannot claim because they are not typed in.
 *
 * <p>Only the student reads and edits their own resume. A placement officer reads the resume an application was <em>made with</em> (a snapshot taken when the student
 * applied, see {@link #snapshotJson}), through that application, so what is reviewed is what was submitted and an officer sees nothing of a student who has not applied.
 */
@Service
public class ResumeService {

    /** The student's record as the system holds it; any figure is null when it cannot be known yet. */
    public record Record(BigDecimal cgpa, Double attendancePercentage, UUID programId, Integer expectedGraduationYear) {
    }

    public record Skill(UUID skillId, String level) {
    }

    /** The student's own resume as it stands now. {@code version} is 0 until it is first saved. */
    public record View(UUID studentId, int version, ResumeContent content, Instant updatedAt, Record record, List<Skill> skills) {
    }

    /** The resume an application was made with, frozen when the student applied. */
    public record Snapshot(Instant capturedAt, int resumeVersion, ResumeContent content, Record record, List<Skill> skills) {
    }

    /** The snapshot as JSON text, ready to store with the application, and the resume version it came from. */
    public record SnapshotJson(String json, int resumeVersion) {
    }

    public sealed interface SaveResult {
        record Saved(int version) implements SaveResult {
        }

        record Invalid(String message) implements SaveResult {
        }

        record Conflict() implements SaveResult {
        }

        record StudentNotFound() implements SaveResult {
        }
    }

    private final StudentResumeRepository resumeRepository;
    private final StudentProfileService studentProfileService;
    private final StudentSkillService studentSkillService;
    private final StudentStandings studentStandings;
    private final CareerAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    ResumeService(StudentResumeRepository resumeRepository, StudentProfileService studentProfileService, StudentSkillService studentSkillService,
                  StudentStandings studentStandings, CareerAccessSupport accessSupport, AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                  ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.studentProfileService = studentProfileService;
        this.studentSkillService = studentSkillService;
        this.studentStandings = studentStandings;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
    }

    /** The student's own resume. Anyone else, whatever their role, is refused. */
    @Transactional(readOnly = true)
    public Optional<View> viewOwn(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> info = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        assertOwn(info.get().tenantId(), studentId);
        Optional<StudentResume> stored = resumeRepository.findByStudentId(studentId);
        return Optional.of(new View(studentId, stored.map(StudentResume::version).orElse(0), stored.map(r -> read(r.content())).orElse(ResumeContent.EMPTY),
            stored.map(StudentResume::updatedAt).orElse(null), record(studentId), skills(studentId)));
    }

    /**
     * Saves the student's own resume. {@code expectedVersion} is the version they were editing (0 or null before the first save): a resume changed in the meantime, say from
     * another window, is a conflict rather than being overwritten.
     */
    @Transactional
    public SaveResult save(UUID studentId, ResumeContent content, Integer expectedVersion) {
        Optional<StudentProfileService.StudentTenantOwner> info = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (info.isEmpty()) {
            return new SaveResult.StudentNotFound();
        }
        UUID tenantId = info.get().tenantId();
        assertOwn(tenantId, studentId);
        ResumeContent c = content == null ? ResumeContent.EMPTY : content;
        try {
            c.validate();
        } catch (IllegalArgumentException e) {
            return new SaveResult.Invalid(e.getMessage());
        }
        int expected = expectedVersion == null ? 0 : expectedVersion;
        Optional<StudentResume> existing = resumeRepository.findByStudentId(studentId);
        int newVersion;
        try {
            if (existing.isEmpty()) {
                if (expected != 0) {
                    return new SaveResult.Conflict();
                }
                newVersion = resumeRepository.saveAndFlush(new StudentResume(UUID.randomUUID(), tenantId, studentId, write(c), Instant.now())).version();
            } else {
                StudentResume resume = existing.get();
                if (resume.version() != expected) {
                    return new SaveResult.Conflict();
                }
                resume.replaceContent(write(c), Instant.now());
                newVersion = resumeRepository.saveAndFlush(resume).version();
            }
        } catch (OptimisticLockingFailureException e) {
            return new SaveResult.Conflict();
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "RESUME_UPDATED", "career.student_resumes", studentId, Map.of("version", newVersion));
        return new SaveResult.Saved(newVersion);
    }

    /** Whether the student has a complete resume (one with a headline). No authorization of its own: the caller is deciding whether this student may apply. */
    @Transactional(readOnly = true)
    boolean hasCompleteResume(UUID studentId) {
        return resumeRepository.findByStudentId(studentId).map(r -> read(r.content()).complete()).orElse(false);
    }

    /** The snapshot to store with an application, or empty if the student has no complete resume. No authorization of its own: the student is applying for themselves. */
    @Transactional(readOnly = true)
    Optional<SnapshotJson> snapshotJson(UUID studentId) {
        return resumeRepository.findByStudentId(studentId).filter(r -> read(r.content()).complete()).map(r ->
            new SnapshotJson(write(new Snapshot(Instant.now(), r.version(), read(r.content()), record(studentId), skills(studentId))), r.version()));
    }

    /** Reads a stored snapshot back. */
    Snapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored resume snapshot could not be read", e);
        }
    }

    private Record record(UUID studentId) {
        return studentStandings.of(studentId).map(s -> new Record(s.cgpa(), s.attendancePercentage(), s.programId(), s.expectedGraduationYear()))
            .orElse(new Record(null, null, null, null));
    }

    private List<Skill> skills(UUID studentId) {
        return studentSkillService.listForStudent(studentId).stream().map(s -> new Skill(s.skillId(), s.proficiencyLevel() == null ? null : s.proficiencyLevel().name())).toList();
    }

    private void assertOwn(UUID tenantId, UUID studentId) {
        Optional<UUID> own = accessSupport.currentStudentId(tenantId);
        if (own.isEmpty() || !own.get().equals(studentId)) {
            throw new AccessDeniedException("permission_denied");
        }
    }

    private ResumeContent read(String json) {
        try {
            return objectMapper.readValue(json, ResumeContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored resume could not be read", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
