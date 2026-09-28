package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.QuestionService;
import com.setu.assessment.domain.Question;
import com.setu.assessment.domain.QuestionType;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 11 §Questions: questions — see QuestionService's javadoc. */
@RestController
@RequestMapping("/assessment/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    public record CreateRequest(UUID componentId, QuestionType type, String prompt, BigDecimal points, String difficulty,
                                 UUID skillId, String metadataJson) {
    }

    /** {@code metadataJson} is omitted from the response entirely for non-manager callers — see QuestionService.listForComponent's javadoc; this controller never re-adds it. */
    public record QuestionResponse(UUID id, UUID componentId, String type, String prompt, BigDecimal points,
                                    String difficulty, UUID skillId, String metadataJson) {
    }

    private static QuestionResponse toResponse(Question q) {
        return new QuestionResponse(q.id(), q.componentId(), q.type() == null ? null : q.type().name(), q.prompt(),
            q.points(), q.difficulty(), q.skillId(), q.metadataJson());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = questionService.create(request.componentId(), request.type(), request.prompt(), request.points(),
            request.difficulty(), request.skillId(), request.metadataJson());
        return switch (result) {
            case QuestionService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(questionService.get(r.questionId()).orElseThrow()));
            case QuestionService.CreateResult.ComponentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("component not found"));
            case QuestionService.CreateResult.NotDraft ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment is no longer in DRAFT"));
            case QuestionService.CreateResult.InvalidQuestionData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping
    public ResponseEntity<List<QuestionResponse>> listForComponent(@RequestParam UUID componentId) {
        return ResponseEntity.ok(questionService.listForComponent(componentId).stream()
            .map(QuestionController::toResponse).toList());
    }

    @DeleteMapping("/{questionId}")
    public ResponseEntity<?> delete(@PathVariable UUID questionId) {
        var result = questionService.delete(questionId);
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case NOT_DRAFT -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment is no longer in DRAFT"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
