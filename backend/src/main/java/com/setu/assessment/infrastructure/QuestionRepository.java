package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    List<Question> findByComponentId(UUID componentId);

    List<Question> findByComponentIdIn(Collection<UUID> componentIds);
}
