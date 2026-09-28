package com.setu.shared.academic;

import java.util.UUID;

/** Cross-module academic-calendar read used by institution.CollegeService's archive validation — see package-info for why this is a shared contract rather than a direct dependency on catalog.application.AcademicPeriodService. */
public interface AcademicCalendarPolicy {

    boolean hasAnyMidTermActivePeriod(UUID tenantId);
}
