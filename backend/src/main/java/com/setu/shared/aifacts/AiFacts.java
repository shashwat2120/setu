package com.setu.shared.aifacts;

import java.util.Map;
import java.util.UUID;

/**
 * The secure tool boundary of the AI advisor (canonical spec section 10). Each method is one fact the advisor may ask for on behalf of the
 * person it is talking to. Three rules hold for every implementation, in-process or remote:
 * <ul>
 *   <li><b>The model never decides who may see what.</b> Parameters name which student a question is about (or are absent, meaning the caller
 *       themselves); whether the caller may see the answer is decided by the application, every time, from the caller's own identity.</li>
 *   <li><b>A refusal is an answer, not an error.</b> A student that is not found or not visible comes back as a structured
 *       {@code {"reason": ...}} result, so the model reports it instead of inventing around it.</li>
 *   <li><b>Unavailable is not empty.</b> If the answer cannot be obtained (the data service is down or slow), the implementation throws
 *       {@link Unavailable}; it never substitutes an empty or made-up result.</li>
 * </ul>
 * Results are plain JSON-serializable values (records, lists, or a {@code JsonNode} that carries them over the network).
 */
public interface AiFacts {

    /** The person asking and the college they are acting in ({@code tenantId} is null for a platform-level account). */
    record Caller(UUID userId, UUID tenantId) {
    }

    /** The fact could not be obtained: the owner of the data did not answer or answered with something unusable. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Caller caller();

    Object studentAttendance(String studentId);

    Object academicProgress(String studentId);

    Object assessmentResults(String studentId);

    Object upcomingDeadlines(String studentId);

    Object skillGap(String jobId, String studentId);

    /** Policy documents of the caller's own college only; the college filter is applied before anything is retrieved. */
    Object policySearch(String query);

    Object learningResources(String studentId);

    Object transcript(String studentId);

    Object feeStatus(String studentId);

    /**
     * Records that the advisor did something for the caller (answered, was rate limited, fell back to another model ...). The user and college
     * recorded are the caller's own, never taken from the arguments.
     */
    void audit(String action, UUID correlationId, Map<String, Object> details);
}
