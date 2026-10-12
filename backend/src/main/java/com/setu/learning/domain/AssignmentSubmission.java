package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps learning.assignment_submissions (V38). {@code (assignment_id,
 * student_id, attempt_no)} is DB-unique — the duplicate-submission
 * concurrency guard, same idiom as attendance_records' composite unique
 * (Day 9). {@code gradeId} is a bare, unenforced reference column only —
 * Day 10 never writes it (Day 11's grading workflow will). Day 10 creates
 * rows directly at {@code SUBMITTED}/{@code LATE} (see {@code
 * AssignmentSubmissionService.submit}'s javadoc for why the {@code DRAFT}
 * value is never actually persisted by this module's flow).
 */
@Entity
@Table(name = "assignment_submissions", schema = "learning")
public class AssignmentSubmission {

    @Id
    private UUID id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "grade_id")
    private UUID gradeId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentSubmissionStatus status;

    @Column(name = "content_ref")
    private String contentRef;

    @Column(name = "marks")
    private BigDecimal marks;

    @Column(name = "feedback")
    private String feedback;

    @Column(name = "graded_by")
    private UUID gradedBy;

    @Column(name = "graded_at")
    private Instant gradedAt;

    @Version
    private int version;

    protected AssignmentSubmission() {
    }

    public AssignmentSubmission(UUID id, UUID assignmentId, UUID studentId, int attemptNo, Instant submittedAt,
                                 AssignmentSubmissionStatus status, String contentRef) {
        this.id = id;
        this.assignmentId = assignmentId;
        this.studentId = studentId;
        this.attemptNo = attemptNo;
        this.submittedAt = submittedAt;
        this.status = status;
        this.contentRef = contentRef;
    }

    public UUID id() {
        return id;
    }

    public UUID assignmentId() {
        return assignmentId;
    }

    public UUID studentId() {
        return studentId;
    }

    public UUID gradeId() {
        return gradeId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public AssignmentSubmissionStatus status() {
        return status;
    }

    public String contentRef() {
        return contentRef;
    }

    public BigDecimal marks() {
        return marks;
    }

    public String feedback() {
        return feedback;
    }

    public UUID gradedBy() {
        return gradedBy;
    }

    public Instant gradedAt() {
        return gradedAt;
    }

    /** Only from SUBMITTED/LATE — grading a DRAFT or already-GRADED row is a caller error the service layer rejects before ever reaching here. */
    public void grade(BigDecimal marks, String feedback, UUID gradedBy, Instant now) {
        this.marks = marks;
        this.feedback = feedback;
        this.gradedBy = gradedBy;
        this.gradedAt = now;
        this.status = AssignmentSubmissionStatus.GRADED;
    }
}
