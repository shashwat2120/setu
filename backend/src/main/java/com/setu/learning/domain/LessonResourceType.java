package com.setu.learning.domain;

/**
 * No canonical vocabulary is documented for {@code lesson_resources.type}
 * either. A resource's media kind, deliberately a separate vocabulary from
 * {@link LessonType} (a VIDEO lesson can carry a DOCUMENT resource, e.g.
 * accompanying slides) — smallest defensible, disclosed set.
 */
public enum LessonResourceType {
    VIDEO, DOCUMENT, IMAGE, AUDIO, LINK
}
