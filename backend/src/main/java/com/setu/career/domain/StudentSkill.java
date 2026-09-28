package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps career.student_skills (V45) — {@code (student_id, skill_id)} unique. Self-service: a student adds/updates/removes their own skills. */
@Entity
@Table(name = "student_skills", schema = "career")
public class StudentSkill {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency_level")
    private ProficiencyLevel proficiencyLevel;

    @Column(name = "evidence_ref")
    private String evidenceRef;

    protected StudentSkill() {
    }

    public StudentSkill(UUID id, UUID tenantId, UUID studentId, UUID skillId, ProficiencyLevel proficiencyLevel, String evidenceRef) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.skillId = skillId;
        this.proficiencyLevel = proficiencyLevel;
        this.evidenceRef = evidenceRef;
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

    public UUID skillId() {
        return skillId;
    }

    public ProficiencyLevel proficiencyLevel() {
        return proficiencyLevel;
    }

    public String evidenceRef() {
        return evidenceRef;
    }

    public void update(ProficiencyLevel proficiencyLevel, String evidenceRef) {
        this.proficiencyLevel = proficiencyLevel;
        this.evidenceRef = evidenceRef;
    }
}
