package com.setu.assessment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps assessment.questions (V39). {@code metadataJson} carries the
 * grading-relevant answer key for auto-gradable types (e.g. a text field
 * named {@code correctOption} for MCQ_SINGLE) — the evaluator reads it
 * server-side only; it is never echoed to a student before/during an
 * attempt and never trusted as anything other than opaque JSON text at the
 * persistence layer (parsed defensively by the evaluator, never
 * deserialized into executable logic — arbitrary raw data must never
 * become trusted grading logic, Day 11 instruction). {@code skillId} is a
 * bare, unvalidated reference — {@code career.skills} is out of Day 11
 * scope entirely.
 */
@Entity
@Table(name = "questions", schema = "assessment")
public class Question {

    @Id
    private UUID id;

    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Enumerated(EnumType.STRING)
    private QuestionType type;

    @Column(nullable = false)
    private String prompt;

    @Column(nullable = false)
    private BigDecimal points;

    private String difficulty;

    @Column(name = "skill_id")
    private UUID skillId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    protected Question() {
    }

    public Question(UUID id, UUID componentId, QuestionType type, String prompt, BigDecimal points, String difficulty,
                     UUID skillId, String metadataJson) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (points == null || points.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("points must be > 0");
        }
        this.id = id;
        this.componentId = componentId;
        this.type = type;
        this.prompt = prompt;
        this.points = points;
        this.difficulty = difficulty;
        this.skillId = skillId;
        this.metadataJson = metadataJson;
    }

    public UUID id() {
        return id;
    }

    public UUID componentId() {
        return componentId;
    }

    public QuestionType type() {
        return type;
    }

    public String prompt() {
        return prompt;
    }

    public BigDecimal points() {
        return points;
    }

    public String difficulty() {
        return difficulty;
    }

    public UUID skillId() {
        return skillId;
    }

    public String metadataJson() {
        return metadataJson;
    }
}
