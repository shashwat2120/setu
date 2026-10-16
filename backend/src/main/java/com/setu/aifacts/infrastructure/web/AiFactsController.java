package com.setu.aifacts.infrastructure.web;

import com.setu.shared.aifacts.AiFacts;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The facts the AI advisor may ask for, as an API: what the AI service calls, over the network, with the <b>user's own bearer token</b>. The
 * request is authenticated like any other request of that user, so every fact is decided for the real principal and tenant by the same code
 * that decides it in one process ({@link AiFacts}); the AI service is trusted with nothing it could abuse. A user can call these endpoints
 * directly and gets exactly what the advisor would have got for them, which is what they could already read elsewhere.
 * <p>
 * A refusal ("student not found", "not authorized ...") is a normal 200 body of the form {@code {"reason": ...}}, as for the in-process tool, so
 * the model reports it; only an unusable request is a 4xx.
 */
@RestController
@RequestMapping("/ai-facts")
public class AiFactsController {

    /** The only things the advisor may record. Anything else is refused, whatever the caller says it is. */
    static final Set<String> AUDIT_ACTIONS = Set.of("AI_CHAT_COMPLETED", "AI_CHAT_RATE_LIMITED", "AI_CHAT_FALLBACK_USED", "AI_CHAT_TIMEOUT",
        "AI_CHAT_PROVIDER_ERROR", "AI_JOB_SUBMITTED", "AI_JOB_COMPLETED", "AI_JOB_FAILED");
    static final int MAX_AUDIT_DETAILS = 20;
    static final int MAX_AUDIT_VALUE_LENGTH = 500;

    public record AuditRequest(String action, UUID correlationId, Map<String, Object> details) {
    }

    private final AiFacts facts;

    public AiFactsController(AiFacts facts) {
        this.facts = facts;
    }

    @GetMapping("/me")
    public AiFacts.Caller me() {
        return facts.caller();
    }

    @GetMapping("/attendance")
    public Object attendance(@RequestParam(required = false) String studentId) {
        return facts.studentAttendance(studentId);
    }

    @GetMapping("/academic-progress")
    public Object academicProgress(@RequestParam(required = false) String studentId) {
        return facts.academicProgress(studentId);
    }

    @GetMapping("/assessment-results")
    public Object assessmentResults(@RequestParam(required = false) String studentId) {
        return facts.assessmentResults(studentId);
    }

    @GetMapping("/deadlines")
    public Object deadlines(@RequestParam(required = false) String studentId) {
        return facts.upcomingDeadlines(studentId);
    }

    @GetMapping("/skill-gap")
    public Object skillGap(@RequestParam String jobId, @RequestParam(required = false) String studentId) {
        return facts.skillGap(jobId, studentId);
    }

    @GetMapping("/policy-search")
    public Object policySearch(@RequestParam String query) {
        return facts.policySearch(query);
    }

    @GetMapping("/learning-resources")
    public Object learningResources(@RequestParam(required = false) String studentId) {
        return facts.learningResources(studentId);
    }

    @GetMapping("/transcript")
    public Object transcript(@RequestParam(required = false) String studentId) {
        return facts.transcript(studentId);
    }

    @GetMapping("/fee-status")
    public Object feeStatus(@RequestParam(required = false) String studentId) {
        return facts.feeStatus(studentId);
    }

    /**
     * The advisor's audit trail. The user and college recorded are the caller's own, taken from the token, never from the body; the action must
     * be one the advisor really performs and the details are small, so this cannot be used to write arbitrary entries into the audit log.
     */
    @PostMapping("/audit")
    public ResponseEntity<Void> audit(@RequestBody AuditRequest request) {
        if (request == null || request.action() == null || !AUDIT_ACTIONS.contains(request.action()) || request.correlationId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Map<String, Object> details = request.details() == null ? Map.of() : request.details();
        if (details.size() > MAX_AUDIT_DETAILS || details.values().stream().anyMatch(v -> String.valueOf(v).length() > MAX_AUDIT_VALUE_LENGTH)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        facts.audit(request.action(), request.correlationId(), details);
        return ResponseEntity.noContent().build();
    }
}
