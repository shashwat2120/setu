package com.setu.directory.infrastructure;

import com.setu.directory.domain.StudentGuardian;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentGuardianRepository extends JpaRepository<StudentGuardian, UUID> {

    List<StudentGuardian> findByStudentId(UUID studentId);

    List<StudentGuardian> findByGuardianId(UUID guardianId);

    Optional<StudentGuardian> findByStudentIdAndGuardianIdAndRelationshipType(UUID studentId, UUID guardianId, String relationshipType);
}
