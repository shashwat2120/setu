package com.setu.shared.attendancefacts;

import com.setu.shared.internalcall.LocalReads;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link AttendanceCounts} everything else is given. It sends each question to the Attendance service when this process is set up to ask it
 * ({@code setu.attendance.url}), to the attendance module's own data when it is not, and to the module's own data inside a {@link LocalReads} scope (the first-start
 * seeding, when the service cannot exist yet). With neither a service to ask nor a module to read, it says so rather than answering.
 */
@Primary
@Component
class AttendanceCountsRouter implements AttendanceCounts {

    private final ObjectProvider<AttendanceCounts> local;
    private final ObjectProvider<RemoteAttendanceCounts> remote;

    AttendanceCountsRouter(@Qualifier("localAttendanceCounts") ObjectProvider<AttendanceCounts> local, ObjectProvider<RemoteAttendanceCounts> remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public Map<UUID, Counts> forEnrollments(Collection<UUID> enrollmentIds) {
        return chosen().forEnrollments(enrollmentIds);
    }

    private AttendanceCounts chosen() {
        AttendanceCounts remoteReader = remote.getIfAvailable();
        AttendanceCounts localReader = local.getIfAvailable();
        if (remoteReader != null && !LocalReads.active()) {
            return remoteReader;
        }
        if (localReader != null) {
            return localReader;
        }
        throw new Unavailable("this process has neither the attendance module nor an Attendance service to ask", null);
    }
}
