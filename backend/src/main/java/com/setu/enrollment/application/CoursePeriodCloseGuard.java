package com.setu.enrollment.application;

import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.EnrollmentRepository;
import com.setu.shared.academic.PeriodCloseGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Enrollment's answer to "may this period close?" (see {@link PeriodCloseGuard}). No authorization of its own: the caller, the period close, has it. */
@Service
public class CoursePeriodCloseGuard implements PeriodCloseGuard {

    private final CourseSectionRepository sectionRepository;
    private final EnrollmentRepository enrollmentRepository;
    /** Looked up when needed, not injected: CourseSectionService depends on the period service, which depends on this guard (a construction cycle otherwise). */
    private final ObjectProvider<CourseSectionService> sectionService;

    public CoursePeriodCloseGuard(CourseSectionRepository sectionRepository, EnrollmentRepository enrollmentRepository,
                                  ObjectProvider<CourseSectionService> sectionService) {
        this.sectionRepository = sectionRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.sectionService = sectionService;
    }

    @Override
    @Transactional(readOnly = true)
    public Blockers blockersFor(UUID periodId) {
        List<OpenSection> open = new ArrayList<>();
        List<UngradedSection> ungraded = new ArrayList<>();
        for (CourseSection section : sectionRepository.findByPeriodId(periodId)) {
            if (section.status() != CourseSectionStatus.CLOSED && section.status() != CourseSectionStatus.CANCELLED) {
                open.add(new OpenSection(section.id(), section.sectionCode(), section.status().name()));
            }
            long enrolled = enrollmentRepository.countBySectionIdAndStatus(section.id(), EnrollmentStatus.ENROLLED);
            if (enrolled > 0) {
                ungraded.add(new UngradedSection(section.id(), section.sectionCode(), enrolled));
            }
        }
        return new Blockers(open, ungraded);
    }

    @Override
    @Transactional
    public int closeOpenSections(UUID periodId) {
        int changed = 0;
        for (CourseSection section : sectionRepository.findByPeriodId(periodId)) {
            CourseSectionStatus target = switch (section.status()) {
                case OPEN -> CourseSectionStatus.CLOSED;
                case DRAFT -> CourseSectionStatus.CANCELLED;
                default -> null;
            };
            if (target != null && sectionService.getObject().changeStatus(section.id(), target) == CourseSectionService.StatusChangeResult.SUCCESS) {
                changed++;
            }
        }
        return changed;
    }
}
