package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Curriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CurriculumRepository extends JpaRepository<Curriculum, UUID> {

    List<Curriculum> findByProgramId(UUID programId);
}
