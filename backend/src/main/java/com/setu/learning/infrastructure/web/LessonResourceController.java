package com.setu.learning.infrastructure.web;

import com.setu.learning.application.LessonResourceService;
import com.setu.learning.domain.LessonResource;
import com.setu.learning.domain.LessonResourceType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Day 10 §3: lesson_resources — see LessonResourceService's javadoc. */
@RestController
@RequestMapping("/learning/resources")
public class LessonResourceController {

    private final LessonResourceService lessonResourceService;

    public LessonResourceController(LessonResourceService lessonResourceService) {
        this.lessonResourceService = lessonResourceService;
    }

    public record AddRequest(UUID lessonId, String mediaRef, LessonResourceType type) {
    }

    public record ResourceResponse(UUID id, UUID lessonId, String mediaRef, String type) {
    }

    private static ResourceResponse toResponse(LessonResource r) {
        return new ResourceResponse(r.id(), r.lessonId(), r.mediaRef(), r.type().name());
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddRequest request) {
        var result = lessonResourceService.add(request.lessonId(), request.mediaRef(), request.type());
        return switch (result) {
            case LessonResourceService.AddResult.Success r -> {
                var added = lessonResourceService.listForLesson(request.lessonId()).stream()
                    .filter(res -> res.id().equals(r.resourceId())).findFirst().orElseThrow();
                yield ResponseEntity.status(HttpStatus.CREATED).body(toResponse(added));
            }
            case LessonResourceService.AddResult.LessonNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("lesson not found"));
            case LessonResourceService.AddResult.InvalidResourceData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping
    public ResponseEntity<List<ResourceResponse>> listForLesson(@RequestParam UUID lessonId) {
        return ResponseEntity.ok(lessonResourceService.listForLesson(lessonId).stream()
            .map(LessonResourceController::toResponse).toList());
    }

    @DeleteMapping("/{resourceId}")
    public ResponseEntity<?> remove(@PathVariable UUID resourceId) {
        var result = lessonResourceService.remove(resourceId);
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
