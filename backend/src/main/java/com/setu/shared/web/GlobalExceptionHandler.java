package com.setu.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Backend-wide {@code @RestControllerAdvice}, added Sprint 5D as a focused
 * audit-driven task (post-Day 11, pre-Day 12): before this class existed,
 * grep found zero {@code @RestControllerAdvice}/{@code @ExceptionHandler}
 * usages anywhere in the codebase. See this package's javadoc for the full
 * audit context and for why the 39 controllers' own per-outcome error
 * records are intentionally left untouched.
 * <p>
 * <b>What this class does not do</b>: it never handles a typed, sealed
 * {@code Result}/{@code Outcome} a service returns — those are ordinary
 * return values, not exceptions, and each controller's own {@code switch}
 * already maps them to the correct status with the correct domain-specific
 * message (RoomOverlap, VersionConflict-as-a-named-outcome,
 * duplicate/idempotent operations, and every other already-intentional
 * translation named in the Day-11-completion instruction all keep working
 * exactly as before — this class never sees them, because they never throw).
 * It only catches exceptions that would otherwise propagate uncaught out of
 * a controller method and fall through to Spring Boot's default
 * {@code /error} handling.
 * <p>
 * <b>Status mapping</b> (see {@link ErrorResponse} for the response shape):
 * <ul>
 *   <li>400 — {@code MethodArgumentNotValidException}/{@code HandlerMethodValidationException}/
 *       {@code ConstraintViolationException} (bean validation, {@code fieldErrors} populated),
 *       {@code HttpMessageNotReadableException} (malformed/unparseable JSON body),
 *       {@code MethodArgumentTypeMismatchException}/{@code MissingServletRequestParameterException}
 *       (a path/query parameter that fails to bind, e.g. a non-UUID id),
 *       {@code IllegalArgumentException} (a domain constructor's own input validation,
 *       e.g. {@code Grade}/{@code Assessment}/{@code AssessmentComponent}'s constructors
 *       throwing on blank/non-positive fields, escaping any call site a service didn't
 *       already wrap in its own try/catch).</li>
 *   <li>401 — {@code AccessDeniedException} whose message is exactly the
 *       string {@code AuthorizationService.currentUserId()} throws for a
 *       missing principal. See the note below on why this single exception
 *       type carries two different meanings in this codebase today.</li>
 *   <li>403 — every other {@code AccessDeniedException} (a real permission
 *       denial, a step-up-required gate, a resource-scope/ownership check
 *       failing, a tenant-isolation check failing) — {@code
 *       AuthorizationService} already throws exactly this type for all of
 *       these today, and this handler does not change that; it only
 *       standardizes the HTTP envelope around it.</li>
 *   <li>404 — {@code NoSuchElementException} (an unqualified {@code
 *       Optional#orElseThrow()} escaping uncaught) and {@code
 *       NoResourceFoundException} (no handler mapped for the request at
 *       all).</li>
 *   <li>409 — {@code OptimisticLockingFailureException} (a {@code @Version}
 *       conflict on any of this codebase's 23 versioned entities that
 *       reaches {@code save()}/flush without a service-level guard —
 *       today none of them have one, so this is the only thing standing
 *       between a real concurrent update and a raw 500),
 *       {@code DataIntegrityViolationException} (a database constraint
 *       violation no service-level {@code catch} already converted to its
 *       own typed outcome — a safety net; 25 services already do this
 *       themselves and this handler never runs for those),
 *       {@code IllegalStateException} (a domain entity's own lifecycle
 *       guard — e.g. {@code AssessmentAttempt#submit}, {@code
 *       Grade#publish} — rejecting an invalid state transition, escaping
 *       uncaught the same way {@code IllegalArgumentException} can).</li>
 *   <li>500 — anything else. The response body never includes {@code
 *       e.getMessage()} or any detail about the exception's type or
 *       origin; the full exception (with its real message and stack
 *       trace) is logged server-side at ERROR, tagged with the same
 *       {@code requestId} returned to the client, so a report quoting
 *       that id is actually traceable without ever exposing internals
 *       over the wire.</li>
 * </ul>
 * <p>
 * The 401/403 split above exists because {@code AuthorizationService}
 * (Day 4, unchanged by this task) uses the same exception type ({@code
 * org.springframework.security.access.AccessDeniedException}) for both a
 * missing principal and a present-but-not-permitted one — an existing,
 * working design this class does not rewrite (that would mean
 * touching every one of AuthorizationService's call sites across ten prior
 * sprint days for a status-code nuance). Distinguishing on the literal
 * message string is the smallest change that gets the two cases onto the
 * correct status code without altering AuthorizationService itself. A
 * fully unqualified, no-token-at-all request never reaches this class at
 * all — Spring Security's own filter chain rejects it before any
 * controller method runs, handled instead by the {@code
 * authenticationEntryPoint} wired in SecurityConfig, which writes the same
 * {@link ErrorResponse} shape directly since that layer runs outside MVC's
 * exception resolution entirely.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String NOT_AUTHENTICATED_MESSAGE = "Not authenticated";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid value");
        }
        return respondValidation(HttpStatus.BAD_REQUEST, "request failed validation", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getAllErrors().forEach(error -> {
            String field = error.getCodes() != null && error.getCodes().length > 0 ? error.getCodes()[0] : "parameter";
            fieldErrors.put(field, error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid value");
        });
        return respondValidation(HttpStatus.BAD_REQUEST, "request failed validation", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> fieldErrors.put(v.getPropertyPath().toString(), v.getMessage()));
        return respondValidation(HttpStatus.BAD_REQUEST, "request failed validation", request, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body could not be parsed", request);
    }

    /** An upload past the servlet's limit (the resume service answers for a file just over its own 2 MB limit in words; this is for anything far larger). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "the file is too large (a resume file may be at most 2 MB)", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "parameter '" + ex.getName() + "' has an invalid value", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "missing required parameter '" + ex.getParameterName() + "'", request);
    }

    /** Added Day 12: PaymentController's Idempotency-Key header is required, and a missing one was previously falling through to the generic 500 safety net instead of this 400. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "missing required header '" + ex.getHeaderName() + "'", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request data is invalid", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        if (NOT_AUTHENTICATED_MESSAGE.equals(ex.getMessage())) {
            return respond(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "authentication is required", request);
        }
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "you do not have permission to perform this operation", request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "the requested resource does not exist", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "the requested resource does not exist", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("optimistic locking conflict on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString());
        return respond(HttpStatus.CONFLICT, "VERSION_CONFLICT",
            "the resource was modified by another request; reload and retry", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("unmapped data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString());
        return respond(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
            "the requested operation conflicts with the current resource state", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "STATE_CONFLICT",
            "the requested operation conflicts with the current resource state", request);
    }

    /** A real client mistake (e.g. GET on a POST-only route) — previously fell through to the 500 safety net below. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "this operation is not supported on this path", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "the request content type is not supported", request);
    }

    /** The owner of some data this answer needs could not be asked: say so (and that trying again may work), never answer with data we do not have. */
    @ExceptionHandler(com.setu.shared.attendancefacts.AttendanceCounts.Unavailable.class)
    public ResponseEntity<ErrorResponse> handleDependencyUnavailable(com.setu.shared.attendancefacts.AttendanceCounts.Unavailable ex, HttpServletRequest request) {
        log.warn("{} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "a service this needs is not answering; try again in a moment", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = currentCorrelationId();
        log.error("unhandled exception on {} {} (requestId={})", request.getMethod(), request.getRequestURI(), requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(500, "INTERNAL_ERROR", "an unexpected error occurred", request.getRequestURI(), requestId));
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(ErrorResponse.of(status.value(), code, message, request.getRequestURI(), currentCorrelationId()));
    }

    private ResponseEntity<ErrorResponse> respondValidation(HttpStatus status, String message, HttpServletRequest request,
                                                              Map<String, String> fieldErrors) {
        return ResponseEntity.status(status)
            .body(ErrorResponse.validation(status.value(), "VALIDATION_FAILED", message, request.getRequestURI(), currentCorrelationId(), fieldErrors));
    }

    /** Day 15 §7: reuses the SAME id {@link CorrelationIdFilter} put in MDC for this whole request — falls back to a fresh one only if that filter somehow never ran (never expected on a real request, defensive only). */
    private static String currentCorrelationId() {
        String fromMdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }
}
