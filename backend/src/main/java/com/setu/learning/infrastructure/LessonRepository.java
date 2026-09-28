package com.setu.learning.infrastructure;

import com.setu.learning.domain.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findByModuleIdOrderBySequenceAsc(UUID moduleId);
}
