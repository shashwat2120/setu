package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps learning.lesson_resources (V37). {@code mediaRef} is an
 * object-storage key/URL — binary media never lives in this table
 * (canonical spec §15, restated in the ERD note for this exact table).
 * Ownership follows the owning {@code lessonId} (and, through it, the
 * lesson's module's section/tenant) — never queried standalone across
 * tenants.
 */
@Entity
@Table(name = "lesson_resources", schema = "learning")
public class LessonResource {

    @Id
    private UUID id;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    @Column(name = "media_ref", nullable = false)
    private String mediaRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LessonResourceType type;

    /** Optional human-readable name, e.g. "Think Python, 2nd edition (PDF)"; null means "show the address". */
    @Column(name = "title")
    private String title;

    protected LessonResource() {
    }

    public LessonResource(UUID id, UUID lessonId, String mediaRef, LessonResourceType type) {
        this(id, lessonId, mediaRef, type, null);
    }

    public LessonResource(UUID id, UUID lessonId, String mediaRef, LessonResourceType type, String title) {
        if (mediaRef == null || mediaRef.isBlank()) {
            throw new IllegalArgumentException("mediaRef must not be blank");
        }
        this.id = id;
        this.lessonId = lessonId;
        this.mediaRef = ContentReferences.required(mediaRef);
        this.type = type;
        this.title = ContentReferences.title(title);
    }

    public UUID id() {
        return id;
    }

    public UUID lessonId() {
        return lessonId;
    }

    public String mediaRef() {
        return mediaRef;
    }

    public String title() {
        return title;
    }

    public LessonResourceType type() {
        return type;
    }
}
