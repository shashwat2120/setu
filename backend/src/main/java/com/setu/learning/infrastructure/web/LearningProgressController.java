package com.setu.learning.infrastructure.web;

import com.setu.learning.application.LearningProgressService;
import com.setu.learning.domain.LearningProgress;
import com.setu.learning.domain.LearningProgressState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 10 §4: learning_progress — see LearningProgressService's javadoc. */
@RestController
@RequestMapping("/learning/progress")
public class LearningProgressController {

    private final LearningProgressService learningProgressService;

    public LearningProgressController(LearningProgressService learningProgressService) {
        this.learningProgressService = learningProgressService;
    }

    public record RecordRequest(UUID lessonId, LearningProgressState state, BigDecimal percent, String lastPosition) {
    }

    public record ProgressResponse(UUID id, UUID lessonId, UUID studentId, String state, BigDecimal percent,
                                    String startedAt, String completedAt, String lastPosition) {
    }

    private static ProgressResponse toResponse(LearningProgress p) {
        return new ProgressResponse(p.id(), p.lessonId(), p.studentId(), p.state() == null ? null : p.state().name(),
            p.percent(), p.startedAt() == null ? null : p.startedAt().toString(),
            p.completedAt() == null ? null : p.completedAt().toString(), p.lastPosition());
    }

    @PostMapping
    public ResponseEntity<?> record(@RequestBody RecordRequest request) {
        var result = learningProgressService.record(request.lessonId(), request.state(), request.percent(), request.lastPosition());
        return switch (result) {
            case LearningProgressService.RecordResult.Success r ->
                ResponseEntity.ok(learningProgressService.getById(r.progressId()).map(LearningProgressController::toResponse).orElseThrow());
            case LearningProgressService.RecordResult.LessonNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("lesson not found"));
            case LearningProgressService.RecordResult.NotEnrolled ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("not actively enrolled in this lesson's section"));
            case LearningProgressService.RecordResult.InvalidProgressData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case LearningProgressService.RecordResult.VersionConflict ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("progress was updated concurrently; reload and retry"));
        };
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestParam UUID lessonId, @RequestParam UUID studentId) {
        return learningProgressService.get(lessonId, studentId).map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/lesson/{lessonId}")
    public ResponseEntity<List<ProgressResponse>> listForLesson(@PathVariable UUID lessonId) {
        return ResponseEntity.ok(learningProgressService.listForLesson(lessonId).stream()
            .map(LearningProgressController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
