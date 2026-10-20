package com.setu.career.infrastructure;

import com.setu.career.domain.StudentResume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudentResumeRepository extends JpaRepository<StudentResume, UUID> {

    Optional<StudentResume> findByStudentId(UUID studentId);
}
