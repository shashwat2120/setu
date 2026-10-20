package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Maps career.student_resumes (V102): one resume per student, kept as a JSON document that the service validates, with an optimistic-locking version. */
@Entity
@Table(name = "student_resumes", schema = "career")
public class StudentResume {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String content;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StudentResume() {
    }

    public StudentResume(UUID id, UUID tenantId, UUID studentId, String content, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.content = content;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID studentId() {
        return studentId;
    }

    /** The resume as JSON text; the service turns it into a {@link ResumeContent}. */
    public String content() {
        return content;
    }

    /**
     * The revision a person sees: 0 for a resume never saved, then 1 for the first save, 2 for the next, and so on. (The row's own version counter starts at 0 for a saved
     * resume, which would be indistinguishable from "never saved" and let two windows both open on an empty resume overwrite each other.)
     */
    public int revision() {
        return version + 1;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void replaceContent(String content, Instant now) {
        this.content = content;
        this.updatedAt = now;
    }
}
