package com.setu.learning.domain;

/**
 * No canonical vocabulary is documented anywhere for {@code lessons.type}
 * (the ERD leaves it a bare nullable TEXT). Smallest defensible, disclosed
 * set of content shapes a lesson's primary body can take — every value
 * still resolves through {@code content_ref} to external/object storage,
 * never inline binary (canonical spec §15).
 */
public enum LessonType {
    VIDEO, DOCUMENT, LINK, TEXT
}
