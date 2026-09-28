package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps career.job_skills (V46) — {@code (job_id, skill_id)} unique. One required-skill row per job; together these form the deterministic eligibility criteria a student is checked against at application time (no separate "eligibility criteria" table exists in the canonical schema — see JobEligibilityService's javadoc). */
@Entity
@Table(name = "job_skills", schema = "career")
public class JobSkill {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_level")
    private ProficiencyLevel requiredLevel;

    protected JobSkill() {
    }

    public JobSkill(UUID id, UUID tenantId, UUID jobId, UUID skillId, ProficiencyLevel requiredLevel) {
        this.id = id;
        this.tenantId = tenantId;
        this.jobId = jobId;
        this.skillId = skillId;
        this.requiredLevel = requiredLevel;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID jobId() {
        return jobId;
    }

    public UUID skillId() {
        return skillId;
    }

    public ProficiencyLevel requiredLevel() {
        return requiredLevel;
    }
}
