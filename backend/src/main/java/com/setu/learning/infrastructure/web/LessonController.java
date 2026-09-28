package com.setu.learning.infrastructure.web;

import com.setu.learning.application.LessonService;
import com.setu.learning.domain.Lesson;
import com.setu.learning.domain.LessonType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Day 10 §2: lessons — see LessonService's javadoc. */
@RestController
@RequestMapping("/learning/lessons")
public class LessonController {

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    public record CreateRequest(UUID moduleId, String title, LessonType type, String contentRef, int sequence,
                                 Integer durationMinutes) {
    }

    public record LessonResponse(UUID id, UUID moduleId, String title, String type, String contentRef, int sequence,
                                  Integer durationMinutes) {
    }

    private static LessonResponse toResponse(Lesson l) {
        return new LessonResponse(l.id(), l.moduleId(), l.title(), l.type() == null ? null : l.type().name(),
            l.contentRef(), l.sequence(), l.durationMinutes());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = lessonService.create(request.moduleId(), request.title(), request.type(), request.contentRef(),
            request.sequence(), request.durationMinutes());
        return switch (result) {
            case LessonService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(lessonService.get(r.lessonId()).map(LessonController::toResponse).orElseThrow());
            case LessonService.CreateResult.ModuleNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("module not found"));
            case LessonService.CreateResult.InvalidLessonData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case LessonService.CreateResult.SequenceAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a lesson already uses this sequence in this module"));
        };
    }

    @GetMapping("/{lessonId}")
    public ResponseEntity<?> get(@PathVariable UUID lessonId) {
        return lessonService.get(lessonId).map(l -> ResponseEntity.ok(toResponse(l)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<LessonResponse>> listForModule(@RequestParam UUID moduleId) {
        return ResponseEntity.ok(lessonService.listForModule(moduleId).stream().map(LessonController::toResponse).toList());
    }

    public record UpdateRequest(String title, LessonType type, String contentRef, int sequence, Integer durationMinutes) {
    }

    @PutMapping("/{lessonId}")
    public ResponseEntity<?> update(@PathVariable UUID lessonId, @RequestBody UpdateRequest request) {
        var result = lessonService.update(lessonId, request.title(), request.type(), request.contentRef(),
            request.sequence(), request.durationMinutes());
        return switch (result) {
            case LessonService.UpdateResult.Success ignored ->
                ResponseEntity.ok(lessonService.get(lessonId).map(LessonController::toResponse).orElseThrow());
            case LessonService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case LessonService.UpdateResult.InvalidLessonData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case LessonService.UpdateResult.SequenceAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a lesson already uses this sequence in this module"));
        };
    }

    @DeleteMapping("/{lessonId}")
    public ResponseEntity<?> delete(@PathVariable UUID lessonId) {
        var result = lessonService.delete(lessonId);
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
