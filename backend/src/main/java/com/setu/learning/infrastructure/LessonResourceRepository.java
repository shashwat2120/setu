package com.setu.learning.infrastructure;

import com.setu.learning.domain.LessonResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LessonResourceRepository extends JpaRepository<LessonResource, UUID> {

    List<LessonResource> findByLessonId(UUID lessonId);
}
