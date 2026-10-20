package com.setu.career.infrastructure.web;

import com.setu.career.application.ResumeService;
import com.setu.career.domain.ResumeContent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A student's own resume. A placement officer reads the resume an application was made with through that application, never from here. */
@RestController
@RequestMapping("/career/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    public record SaveRequest(UUID studentId, String headline, String summary, String linkedinUrl, String githubUrl, String portfolioUrl, List<ResumeContent.Item> items,
                              Integer expectedVersion) {
    }

    public record ErrorResponse(String message) {
    }

    public record Saved(int version) {
    }

    @GetMapping
    public ResponseEntity<?> mine(@RequestParam UUID studentId) {
        return resumeService.viewOwn(studentId).<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest request) {
        var content = new ResumeContent(request.headline(), request.summary(), request.linkedinUrl(), request.githubUrl(), request.portfolioUrl(), request.items());
        return switch (resumeService.save(request.studentId(), content, request.expectedVersion())) {
            case ResumeService.SaveResult.Saved s -> ResponseEntity.ok(new Saved(s.version()));
            case ResumeService.SaveResult.Invalid i -> ResponseEntity.badRequest().body(new ErrorResponse(i.message()));
            case ResumeService.SaveResult.Conflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("your resume changed in another window; reload and try again"));
            case ResumeService.SaveResult.StudentNotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }
}
