package com.setu.shared.attendancefacts;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How many sessions an enrollment was present (or late) for, out of how many it has a record for: the one thing the risk engine, the AI advisor's attendance tool and Career's
 * standing need from attendance. A port, so that they do not read the {@code attendance} schema: in the monolith the answer comes from the attendance module in-process
 * ({@code LocalAttendanceCounts}); once Attendance has a database of its own it comes from the Attendance service's internal endpoint ({@link RemoteAttendanceCounts},
 * switched on by {@code setu.attendance.url}).
 * <p>
 * No authorization of its own, like the method it replaces ({@code AttendanceRecordService.percentageForEnrollmentUnchecked}): the caller has already authorized its outer
 * operation. The remote one carries the service-to-service token, and the endpoint is not routed by the gateway.
 */
public interface AttendanceCounts {

    record Counts(long presentOrLate, long total) {
        public static final Counts NONE = new Counts(0, 0);
    }

    /**
     * The counts of each enrollment asked about; an enrollment with no records is in the answer with {@link Counts#NONE}.
     *
     * @throws Unavailable if the owner could not be asked or answered something unusable: never a guess, never zeros standing in for an answer
     */
    Map<UUID, Counts> forEnrollments(Collection<UUID> enrollmentIds);

    /** The owner of the data could not give an answer. Callers say so (retry the event, tell the person) and never substitute data. */
    class Unavailable extends com.setu.shared.internalcall.DependencyUnavailableException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
