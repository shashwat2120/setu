package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps learning.lessons (V37). {@code contentRef} is always an
 * external/object-storage reference (a key or URL), never lesson body bytes
 * — binary media stays outside PostgreSQL (canonical spec §15). Belongs to
 * exactly one module; {@code (module_id, sequence)} is DB-unique.
 */
@Entity
@Table(name = "lessons", schema = "learning")
public class Lesson {

    @Id
    private UUID id;

    @Column(name = "module_id", nullable = false)
    private UUID moduleId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    private LessonType type;

    @Column(name = "content_ref")
    private String contentRef;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    protected Lesson() {
    }

    public Lesson(UUID id, UUID moduleId, String title, LessonType type, String contentRef, int sequence,
                  Integer durationMinutes) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = id;
        this.moduleId = moduleId;
        this.title = title;
        this.type = type;
        this.contentRef = contentRef;
        this.sequence = sequence;
        this.durationMinutes = durationMinutes;
    }

    public UUID id() {
        return id;
    }

    public UUID moduleId() {
        return moduleId;
    }

    public String title() {
        return title;
    }

    public LessonType type() {
        return type;
    }

    public String contentRef() {
        return contentRef;
    }

    public int sequence() {
        return sequence;
    }

    public Integer durationMinutes() {
        return durationMinutes;
    }

    public void update(String title, LessonType type, String contentRef, int sequence, Integer durationMinutes) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
        this.type = type;
        this.contentRef = contentRef;
        this.sequence = sequence;
        this.durationMinutes = durationMinutes;
    }
}
