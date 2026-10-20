package com.setu.shared.academic;

import java.util.List;
import java.util.UUID;

/**
 * What stands between an academic period and its close, asked of the module that owns sections and enrollments. Catalog owns the period and enrollment
 * owns what happens inside it, and enrollment already depends on catalog, so catalog cannot depend back (a cycle); this contract is implemented by
 * enrollment and consumed by catalog, the same shape as {@link AcademicCalendarPolicy} (see this package's package-info).
 *
 * <p>The rule (docs/state-machines.md #6): a period closes only when every section in it is CLOSED or CANCELLED and no student in it is still ENROLLED.
 * A student leaves ENROLLED by a final grade being published (the enrollment COMPLETES) or by the registrar dropping them, so a close can never silently
 * decide a student's result.
 */
public interface PeriodCloseGuard {

    /** A section that is not yet CLOSED or CANCELLED. */
    record OpenSection(UUID sectionId, String sectionCode, String status) {
    }

    /** A section that still has students ENROLLED, i.e. with no final grade published yet and not dropped. */
    record UngradedSection(UUID sectionId, String sectionCode, long enrolledStudents) {
    }

    record Blockers(List<OpenSection> openSections, List<UngradedSection> ungradedSections) {
        public boolean none() {
            return openSections.isEmpty() && ungradedSections.isEmpty();
        }
    }

    Blockers blockersFor(UUID periodId);

    /**
     * Closes the period's OPEN sections and cancels its DRAFT ones (a draft was never opened, so it has nobody in it), each as the ordinary audited
     * section status change. Sections that still have students ENROLLED are closed too: closing a section does not decide any student's result, and
     * those students remain a blocker until they are graded or dropped. Returns how many sections changed.
     */
    int closeOpenSections(UUID periodId);
}
