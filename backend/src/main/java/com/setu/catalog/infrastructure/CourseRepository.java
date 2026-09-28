package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CourseRepository extends JpaRepository<Course, UUID> {

    Optional<Course> findByTenantIdAndCode(UUID tenantId, String code);

    Page<Course> findByTenantId(UUID tenantId, Pageable pageable);
}
