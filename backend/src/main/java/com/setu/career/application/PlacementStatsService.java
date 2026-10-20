package com.setu.career.application;

import com.setu.career.domain.ApplicationStatus;
import com.setu.career.domain.PlacementRecord;
import com.setu.career.infrastructure.ApplicationRepository;
import com.setu.shared.career.StudentCohorts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The placement statistics page's figures for one college: the placements (who is placed, in which programs, at what package, with whom), the applications by stage, and the
 * jobs by whether they still take applications. Read access is that of the placement records themselves ({@code VIEW_PLACEMENT_RECORDS}: the placement officer and the
 * administrator), checked by listing the college's placements first.
 */
@Service
public class PlacementStatsService {

    public record Funnel(int submitted, int shortlisted, int selected, int rejected, int withdrawn) {
    }

    public record Jobs(int total, int open, int closedByOfficer, int deadlinePassed, int full) {
    }

    public record Stats(PlacementStatistics.Result placements, Funnel applications, Jobs jobs) {
    }

    private final PlacementRecordService placementRecordService;
    private final ApplicationRepository applicationRepository;
    private final JobService jobService;
    private final JobAvailability jobAvailability;
    private final StudentCohorts studentCohorts;

    PlacementStatsService(PlacementRecordService placementRecordService, ApplicationRepository applicationRepository, JobService jobService, JobAvailability jobAvailability,
                          StudentCohorts studentCohorts) {
        this.placementRecordService = placementRecordService;
        this.applicationRepository = applicationRepository;
        this.jobService = jobService;
        this.jobAvailability = jobAvailability;
        this.studentCohorts = studentCohorts;
    }

    @Transactional(readOnly = true)
    public Stats forTenant(UUID tenantId) {
        List<PlacementRecord> placements = placementRecordService.listForTenant(tenantId); // authorizes: VIEW_PLACEMENT_RECORDS
        var placed = PlacementStatistics.compute(placements, studentCohorts.activeStudentsByProgram(tenantId),
            studentCohorts.activeProgramOf(placements.stream().map(PlacementRecord::studentId).distinct().toList()));
        return new Stats(placed, funnel(tenantId), jobs(tenantId));
    }

    private Funnel funnel(UUID tenantId) {
        Map<ApplicationStatus, Integer> counts = new EnumMap<>(ApplicationStatus.class);
        for (Object[] row : applicationRepository.countByStatusForTenant(tenantId)) {
            counts.put((ApplicationStatus) row[0], ((Number) row[1]).intValue());
        }
        return new Funnel(counts.getOrDefault(ApplicationStatus.SUBMITTED, 0), counts.getOrDefault(ApplicationStatus.SHORTLISTED, 0),
            counts.getOrDefault(ApplicationStatus.SELECTED, 0), counts.getOrDefault(ApplicationStatus.REJECTED, 0), counts.getOrDefault(ApplicationStatus.WITHDRAWN, 0));
    }

    private Jobs jobs(UUID tenantId) {
        var jobs = jobService.listForTenant(tenantId);
        Map<String, Long> byReason = jobAvailability.forJobs(jobs, Instant.now()).values().stream()
            .collect(Collectors.groupingBy(s -> s.closedReason() == null ? "OPEN" : s.closedReason(), Collectors.counting()));
        return new Jobs(jobs.size(), byReason.getOrDefault("OPEN", 0L).intValue(), byReason.getOrDefault(JobAvailability.CLOSED, 0L).intValue(),
            byReason.getOrDefault(JobAvailability.DEADLINE_PASSED, 0L).intValue(), byReason.getOrDefault(JobAvailability.FULL, 0L).intValue());
    }
}
