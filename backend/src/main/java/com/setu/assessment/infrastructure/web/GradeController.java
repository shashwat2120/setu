package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.GradeService;
import com.setu.assessment.domain.Grade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 11 §Grades/§Publication/§Correction: assessment.grades — see GradeService's javadoc. One endpoint handles both first publication and correction; the response's {@code isCorrection} field tells the caller which happened. */
@RestController
@RequestMapping("/assessment/grades")
public class GradeController {

    private final GradeService gradeService;

    public GradeController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    public record PublishRequest(UUID assessmentId, UUID componentId, UUID studentId, BigDecimal rawMarks,
                                  String gradeCode, BigDecimal gradePoint) {
    }

    public record GradeResponse(UUID id, UUID tenantId, UUID enrollmentId, UUID componentId, BigDecimal rawMarks,
                                 String gradeCode, BigDecimal gradePoint, String status, int version,
                                 UUID supersedesId, boolean isCorrection) {
    }

    @PostMapping
    public ResponseEntity<?> publishOrCorrect(@RequestBody PublishRequest request) {
        var result = gradeService.publishOrCorrect(request.assessmentId(), request.componentId(), request.studentId(),
            request.rawMarks(), request.gradeCode(), request.gradePoint());
        return switch (result) {
            case GradeService.PublishResult.Success r -> {
                var grade = gradeService.getCurrent(gradeEnrollmentOf(r), request.componentId());
                yield ResponseEntity.status(r.isCorrection() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(grade.map(g -> toResponse(g, r.isCorrection())).orElseThrow());
            }
            case GradeService.PublishResult.AssessmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("assessment not found"));
            case GradeService.PublishResult.ComponentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("component not found"));
            case GradeService.PublishResult.ComponentNotInAssessment ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("component does not belong to this assessment"));
            case GradeService.PublishResult.NotEnrolled ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student is not actively enrolled in this assessment's section"));
            case GradeService.PublishResult.InvalidGradeData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    /** publishOrCorrect's Success doesn't carry enrollmentId directly (it never trusts a client-supplied one) — re-derived via the grade row itself. */
    private UUID gradeEnrollmentOf(GradeService.PublishResult.Success success) {
        return gradeService.findEnrollmentIdForGrade(success.gradeId());
    }

    private static GradeResponse toResponse(Grade g, boolean isCorrection) {
        return new GradeResponse(g.id(), g.tenantId(), g.enrollmentId(), g.componentId(), g.rawMarks(), g.gradeCode(),
            g.gradePoint(), g.status().name(), g.version(), g.supersedesId(), isCorrection);
    }

    @GetMapping
    public ResponseEntity<?> getCurrent(@RequestParam UUID enrollmentId, @RequestParam UUID componentId) {
        return gradeService.getCurrent(enrollmentId, componentId)
            .map(g -> ResponseEntity.ok(toResponse(g, g.version() > 1)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<GradeResponse>> history(@RequestParam UUID enrollmentId, @RequestParam UUID componentId) {
        return ResponseEntity.ok(gradeService.history(enrollmentId, componentId).stream()
            .map(g -> toResponse(g, g.version() > 1)).toList());
    }

    public record ErrorResponse(String message) {
    }
}
