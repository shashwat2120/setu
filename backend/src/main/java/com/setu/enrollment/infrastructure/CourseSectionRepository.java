package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.CourseSectionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CourseSectionRepository extends JpaRepository<CourseSection, UUID> {

    Optional<CourseSection> findByTenantIdAndCourseIdAndPeriodIdAndSectionCode(
            UUID tenantId, UUID courseId, UUID periodId, String sectionCode);

    Page<CourseSection> findByTenantId(UUID tenantId, Pageable pageable);

    java.util.List<CourseSection> findByPeriodId(UUID periodId);

    Page<CourseSection> findByTenantIdAndStatus(UUID tenantId, CourseSectionStatus status, Pageable pageable);

    /**
     * transaction-concurrency-matrix.md scenario 1: the last-seat enrollment
     * race is serialized by taking a row lock on the SECTION (the race is
     * over its derived remaining-capacity count, not any single enrollment
     * row) — {@code PESSIMISTIC_WRITE} compiles to {@code SELECT ... FOR
     * UPDATE}. Held for the whole enrollment invariant-chain transaction,
     * released at commit/rollback.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CourseSection s where s.id = :id")
    Optional<CourseSection> findByIdForUpdate(@Param("id") UUID id);
}
