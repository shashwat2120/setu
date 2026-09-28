package com.setu.shared.enrollment;

import java.util.Optional;
import java.util.UUID;

/** Cross-module course-section tenant lookup used by catalog.MeetingPatternService — see package-info for why this is a shared contract rather than a direct dependency on enrollment.application.CourseSectionService. */
public interface CourseSectionDirectory {

    Optional<UUID> findTenantId(UUID sectionId);
}
