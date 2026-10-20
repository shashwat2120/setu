package com.setu.career.infrastructure.web;

import com.setu.career.application.PlacementRecordService;
import com.setu.career.domain.PlacementRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/career/placement-records")
public class PlacementRecordController {

    private final PlacementRecordService placementRecordService;

    public PlacementRecordController(PlacementRecordService placementRecordService) {
        this.placementRecordService = placementRecordService;
    }

    public record RecordRequest(UUID studentId, UUID jobId, String employer, String role, BigDecimal ctc, LocalDate placedAt) {
    }

    public record PlacementRecordResponse(UUID id, UUID tenantId, UUID studentId, UUID jobId, String employer, String role,
                                           BigDecimal ctc, LocalDate placedAt, LocalDate joiningDate) {
    }

    private static PlacementRecordResponse toResponse(PlacementRecord p) {
        return new PlacementRecordResponse(p.id(), p.tenantId(), p.studentId(), p.jobId(), p.employer(), p.role(), p.ctc(), p.placedAt(), p.joiningDate());
    }

    @PostMapping
    public ResponseEntity<?> record(@RequestBody RecordRequest request) {
        var result = placementRecordService.record(request.studentId(), request.jobId(), request.employer(), request.role(),
            request.ctc(), request.placedAt());
        return switch (result) {
            case PlacementRecordService.RecordResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(placementRecordService.listForStudent(request.studentId()).stream()
                    .filter(p -> p.id().equals(r.placementId())).findFirst().map(PlacementRecordController::toResponse).orElseThrow());
            case PlacementRecordService.RecordResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case PlacementRecordService.RecordResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    /** {@code studentId} for a student's own history; {@code tenantId} for the Placement Officer's tenant-wide overview. Exactly one is expected. */
    @GetMapping
    public ResponseEntity<List<PlacementRecordResponse>> list(@RequestParam(required = false) UUID studentId,
                                                                @RequestParam(required = false) UUID tenantId) {
        List<PlacementRecord> records = studentId != null
            ? placementRecordService.listForStudent(studentId)
            : placementRecordService.listForTenant(tenantId);
        return ResponseEntity.ok(records.stream().map(PlacementRecordController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
