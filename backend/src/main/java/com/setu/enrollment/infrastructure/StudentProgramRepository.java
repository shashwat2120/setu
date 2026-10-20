package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.StudentProgram;
import com.setu.enrollment.domain.StudentProgramStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentProgramRepository extends JpaRepository<StudentProgram, UUID> {

    List<StudentProgram> findByStudentId(UUID studentId);

    List<StudentProgram> findByTenantIdAndStatus(UUID tenantId, StudentProgramStatus status);

    List<StudentProgram> findByStudentIdInAndStatus(java.util.Collection<UUID> studentIds, StudentProgramStatus status);

    Optional<StudentProgram> findByTenantIdAndStudentIdAndStatusAndPrimaryTrue(UUID tenantId, UUID studentId, StudentProgramStatus status);
}
