package com.setu.career.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.career.domain.ResumeContent;
import com.setu.career.domain.ResumePdf;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A student's resume: what they say about themselves (a headline, a summary, links, projects, certifications, experience) and optionally a PDF of their own, kept with the
 * facts the system itself holds about them (their CGPA, attendance, graduating batch, program and skills), which a resume cannot claim because they are not typed in.
 *
 * <p>Only the student reads and edits their own resume. A placement officer reads the resume an application was <em>made with</em> (a snapshot taken when the student
 * applied, see {@link #snapshotJson}, which also points at the exact PDF they had then), through that application, so what is reviewed is what was submitted and an officer
 * sees nothing of a student who has not applied.
 */
@Service
public class ResumeService {

    /** The student's record as the system holds it; any figure is null when it cannot be known yet. */
    public record Record(BigDecimal cgpa, Double attendancePercentage, UUID programId, Integer expectedGraduationYear) {
    }

    public record Skill(UUID skillId, String level) {
    }

    /** An uploaded resume file: the name to show, its size in bytes, and the hash of its content (its name in storage). */
    public record FileInfo(String name, int size, String sha256) {
    }

    /** The student's own resume as it stands now. {@code version} is 0 until it is first saved. */
    public record View(UUID studentId, int version, ResumeContent content, Instant updatedAt, Record record, List<Skill> skills, FileInfo file) {
    }

    /** The resume an application was made with, frozen when the student applied. {@code file} is null if they had no PDF then. */
    public record Snapshot(Instant capturedAt, int resumeVersion, ResumeContent content, Record record, List<Skill> skills, FileInfo file) {
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

    public sealed interface UploadResult {
        record Stored(FileInfo file, int version) implements UploadResult {
        }

        record Invalid(String message) implements UploadResult {
        }

        record StudentNotFound() implements UploadResult {
        }
    }

    /** A resume file to hand to a person: the name to offer it under, and its bytes. */
    public record Download(String name, byte[] bytes) {
    }

    private final StudentResumeRepository resumeRepository;
    private final StudentProfileService studentProfileService;
    private final StudentSkillService studentSkillService;
    private final StudentStandings studentStandings;
    private final CareerAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;
    private final ResumeFileStore fileStore;

    ResumeService(StudentResumeRepository resumeRepository, StudentProfileService studentProfileService, StudentSkillService studentSkillService,
                  StudentStandings studentStandings, CareerAccessSupport accessSupport, AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                  ObjectMapper objectMapper, ResumeFileStore fileStore) {
        this.resumeRepository = resumeRepository;
        this.studentProfileService = studentProfileService;
        this.studentSkillService = studentSkillService;
        this.studentStandings = studentStandings;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
        this.fileStore = fileStore;
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
        return Optional.of(new View(studentId, stored.map(StudentResume::revision).orElse(0), stored.map(r -> read(r.content())).orElse(ResumeContent.EMPTY),
            stored.map(StudentResume::updatedAt).orElse(null), record(studentId), skills(studentId), stored.map(ResumeService::fileOf).orElse(null)));
    }

    /**
     * Saves the student's own resume. {@code expectedVersion} is the revision they were editing (0 or null before the first save): a resume changed in the meantime, say from
     * another window, is a conflict rather than being overwritten. An uploaded PDF is not touched by this.
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
                newVersion = resumeRepository.saveAndFlush(new StudentResume(UUID.randomUUID(), tenantId, studentId, write(c), Instant.now())).revision();
            } else {
                StudentResume resume = existing.get();
                if (resume.revision() != expected) {
                    return new SaveResult.Conflict();
                }
                resume.replaceContent(write(c), Instant.now());
                newVersion = resumeRepository.saveAndFlush(resume).revision();
            }
        } catch (OptimisticLockingFailureException e) {
            return new SaveResult.Conflict();
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "RESUME_UPDATED", "career.student_resumes", studentId, Map.of("version", newVersion));
        return new SaveResult.Saved(newVersion);
    }

    /**
     * Stores the PDF the student uploads as their resume file (replacing any earlier one; applications already made keep the one they were made with). The file is judged by
     * its content: see {@link ResumePdf}. {@code sentName} is only ever shown, after being cleaned, and never used as a path.
     */
    @Transactional
    public UploadResult uploadFile(UUID studentId, String sentName, byte[] bytes) {
        Optional<StudentProfileService.StudentTenantOwner> info = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (info.isEmpty()) {
            return new UploadResult.StudentNotFound();
        }
        UUID tenantId = info.get().tenantId();
        assertOwn(tenantId, studentId);
        try {
            ResumePdf.check(bytes);
        } catch (IllegalArgumentException e) {
            return new UploadResult.Invalid(e.getMessage());
        }
        String sha256 = ResumePdf.sha256(bytes);
        String name = ResumePdf.displayName(sentName);
        try {
            fileStore.put(tenantId, sha256, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("the resume file could not be stored", e);
        }
        Instant now = Instant.now();
        StudentResume resume = resumeRepository.findByStudentId(studentId).orElseGet(() -> new StudentResume(UUID.randomUUID(), tenantId, studentId, write(ResumeContent.EMPTY), now));
        resume.attachFile(sha256, name, bytes.length, now);
        int version;
        try {
            version = resumeRepository.saveAndFlush(resume).revision();
        } catch (OptimisticLockingFailureException e) {
            return new UploadResult.Invalid("your resume changed at the same moment; try again");
        }
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "RESUME_FILE_UPLOADED", "career.student_resumes", studentId,
            Map.of("size", bytes.length, "sha256", sha256));
        return new UploadResult.Stored(new FileInfo(name, bytes.length, sha256), version);
    }

    /** Removes the student's resume file from their resume (applications already made keep theirs). Returns whether there was one. */
    @Transactional
    public boolean removeFile(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> info = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (info.isEmpty()) {
            return false;
        }
        assertOwn(info.get().tenantId(), studentId);
        Optional<StudentResume> resume = resumeRepository.findByStudentId(studentId).filter(r -> r.fileSha256() != null);
        if (resume.isEmpty()) {
            return false;
        }
        resume.get().clearFile(Instant.now());
        resumeRepository.saveAndFlush(resume.get());
        auditLogWriter.write(info.get().tenantId(), authorizationContext.currentUserId(), "RESUME_FILE_REMOVED", "career.student_resumes", studentId, Map.of());
        return true;
    }

    /** The student's own resume file, for them to download. */
    @Transactional(readOnly = true)
    public Optional<Download> downloadOwnFile(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> info = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        assertOwn(info.get().tenantId(), studentId);
        return resumeRepository.findByStudentId(studentId).map(ResumeService::fileOf).flatMap(f -> load(info.get().tenantId(), f));
    }

    /** The bytes of a file an application's snapshot points at. No authorization of its own: the caller has authorized the reading of that application. */
    Optional<Download> load(UUID tenantId, FileInfo file) {
        if (file == null) {
            return Optional.empty();
        }
        try {
            return fileStore.get(tenantId, file.sha256()).map(bytes -> new Download(file.name(), bytes));
        } catch (IOException e) {
            throw new IllegalStateException("the resume file could not be read", e);
        }
    }

    /** Whether the student has a resume an officer can read: a headline, or a PDF. No authorization of its own: the caller is deciding whether this student may apply. */
    @Transactional(readOnly = true)
    boolean hasCompleteResume(UUID studentId) {
        return resumeRepository.findByStudentId(studentId).map(this::readable).orElse(false);
    }

    /** The snapshot to store with an application, or empty if the student has no resume to send. No authorization of its own: the student is applying for themselves. */
    @Transactional(readOnly = true)
    Optional<SnapshotJson> snapshotJson(UUID studentId) {
        return resumeRepository.findByStudentId(studentId).filter(this::readable).map(r ->
            new SnapshotJson(write(new Snapshot(Instant.now(), r.revision(), read(r.content()), record(studentId), skills(studentId), fileOf(r))), r.revision()));
    }

    /** Reads a stored snapshot back. */
    Snapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored resume snapshot could not be read", e);
        }
    }

    /** A resume is readable when it has a headline or a PDF. */
    private boolean readable(StudentResume r) {
        return r.fileSha256() != null || read(r.content()).complete();
    }

    private static FileInfo fileOf(StudentResume r) {
        return r.fileSha256() == null ? null : new FileInfo(r.fileName(), r.fileSize(), r.fileSha256());
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
