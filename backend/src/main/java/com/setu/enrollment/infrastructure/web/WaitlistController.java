package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.WaitlistService;
import com.setu.enrollment.domain.WaitlistEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/enrollment/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    public record WaitlistEntryResponse(UUID id, UUID tenantId, UUID sectionId, UUID studentId, int position,
                                         String status, Instant createdAt) {
    }

    private static WaitlistEntryResponse toResponse(WaitlistEntry e) {
        return new WaitlistEntryResponse(e.id(), e.tenantId(), e.sectionId(), e.studentId(), e.position(),
            e.status().name(), e.createdAt());
    }

    @GetMapping("/section/{sectionId}")
    public ResponseEntity<List<WaitlistEntryResponse>> listForSection(@PathVariable UUID sectionId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(waitlistService.listForSection(sectionId, tenantId).stream().map(WaitlistController::toResponse).toList());
    }

    @GetMapping("/section/{sectionId}/student/{studentId}")
    public ResponseEntity<?> getForStudent(@PathVariable UUID sectionId, @PathVariable UUID studentId) {
        return waitlistService.getForStudent(sectionId, studentId).map(e -> ResponseEntity.ok(toResponse(e)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/section/{sectionId}/student/{studentId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable UUID sectionId, @PathVariable UUID studentId) {
        return switch (waitlistService.withdraw(sectionId, studentId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("waitlist entry is not withdrawable"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
