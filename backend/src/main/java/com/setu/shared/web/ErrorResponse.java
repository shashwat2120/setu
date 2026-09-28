package com.setu.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The one error shape every centralized (exception-driven) response uses —
 * see this package's javadoc for how this relates to the 39 controllers'
 * own pre-existing, narrower error records, which this does not replace.
 * <p>
 * {@code code} is a small closed set of stable machine-readable strings
 * (see {@link GlobalExceptionHandler}'s javadoc for the full mapping), safe
 * for a client to branch on. {@code message} is always a fixed, safe,
 * human-readable string for its category — never a raw exception message,
 * never a database or stack detail. {@code requestId} is generated fresh
 * per error response (this codebase has no existing HTTP-level
 * correlation-id mechanism to reuse — only per-domain-event correlation
 * ids, which are a different concept) so a client or support engineer can
 * quote it back when reporting an issue; the same id is logged
 * server-side alongside the full exception for that correlation to be
 * useful. {@code fieldErrors} is populated only for validation failures
 * (field name to a safe, specific message) and omitted entirely otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    String requestId,
    List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, String code, String message, String path, String requestId) {
        return new ErrorResponse(Instant.now(), status, code, message, path, requestId, null);
    }

    public static ErrorResponse validation(int status, String code, String message, String path, String requestId,
                                            Map<String, String> fieldErrors) {
        List<FieldError> errors = fieldErrors.entrySet().stream()
            .map(e -> new FieldError(e.getKey(), e.getValue()))
            .toList();
        return new ErrorResponse(Instant.now(), status, code, message, path, requestId, errors);
    }
}
