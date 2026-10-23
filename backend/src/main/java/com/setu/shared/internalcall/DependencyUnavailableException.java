package com.setu.shared.internalcall;

/**
 * The service that owns some data could not be asked, or answered something unusable. Every port that reads another service's data over an internal endpoint
 * ({@code AttendanceCounts}, {@code AssessmentFacts}) throws a subclass of this, so that one handler turns it into a 503 and no caller ever substitutes data it did not get.
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
