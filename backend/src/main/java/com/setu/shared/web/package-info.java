/**
 * The centralized HTTP error contract ({@link com.setu.shared.web.ErrorResponse})
 * and {@link com.setu.shared.web.GlobalExceptionHandler}. See the parent
 * {@code shared} package's javadoc for why this lives here rather than
 * being module-owned: every controller in every module needs the same
 * error shape, so this is exactly the kind of concern the ERD documents as
 * not owned by any single module.
 * <p>
 * Added Sprint 5D (post-Day 11) audit: before this package existed there
 * was no {@code @RestControllerAdvice} anywhere in the codebase. 39
 * controllers each declared their own private, identically-shaped error
 * record carrying only a message string, one per controller, for their
 * typed business-outcome results (a domain service returning a sealed
 * Result that a controller's switch turns into a specific 4xx response).
 * Those per-controller records are deliberately left alone here. They
 * represent genuine domain semantics already correctly translated by each
 * service, not something a global handler should absorb (see
 * GlobalExceptionHandler's javadoc for exactly what it does and does not
 * take over).
 * <p>
 * This package handles the other half: HTTP-layer and infrastructure
 * exceptions that were never caught anywhere (bean-validation failures,
 * malformed JSON, a permission check throwing mid-request, a stale
 * optimistic-locked entity, a database constraint violation nothing
 * upstream anticipated, any unexpected runtime failure). Before this
 * package existed, all of those fell through to Spring Boot's default
 * error handling, which is safe (no leakage by default) but produces a
 * completely different JSON shape from the 39 controllers' own records,
 * and in at least one case (a business not-authenticated check reusing
 * Spring Security's AccessDeniedException) maps to the wrong status code
 * entirely.
 */
package com.setu.shared.web;
