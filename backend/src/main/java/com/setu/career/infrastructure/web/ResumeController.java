package com.setu.career.infrastructure.web;

import com.setu.career.application.ResumeService;
import com.setu.career.domain.ResumeContent;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    /** The PDF a student uploads as their resume file: judged by its content, at most 2 MB. */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam UUID studentId, @RequestParam("file") MultipartFile file) throws IOException {
        return switch (resumeService.uploadFile(studentId, file.getOriginalFilename(), file.getBytes())) {
            case ResumeService.UploadResult.Stored s -> ResponseEntity.ok(new Uploaded(s.file(), s.version()));
            case ResumeService.UploadResult.Invalid i -> ResponseEntity.badRequest().body(new ErrorResponse(i.message()));
            case ResumeService.UploadResult.StudentNotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record Uploaded(ResumeService.FileInfo file, int version) {
    }

    /** The student's own resume file, as a download. */
    @GetMapping("/file")
    public ResponseEntity<?> download(@RequestParam UUID studentId) {
        return resumeService.downloadOwnFile(studentId).<ResponseEntity<?>>map(ResumeController::pdfDownload).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @DeleteMapping("/file")
    public ResponseEntity<?> remove(@RequestParam UUID studentId) {
        return resumeService.removeFile(studentId) ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * A resume file as something to save, never something to display in the application: a fixed type, an attachment, no sniffing, no caching, and a policy that lets nothing
     * in it run or load. The file name is the cleaned one the file was stored under.
     */
    public static ResponseEntity<byte[]> pdfDownload(ResumeService.Download file) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .contentLength(file.bytes().length)
            .body(file.bytes());
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
