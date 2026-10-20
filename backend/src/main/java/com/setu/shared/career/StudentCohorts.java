package com.setu.shared.career;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How many students a college has in each program, and which program each of some students is in: what the placement statistics need to say "placed 42 of 120 in this program".
 * Those facts live in Enrollment, and Career reaching into it would couple the two, so Career asks this port and the module that already reads Enrollment (AI facts) answers it.
 * The caller has authorized its own operation (an officer or an administrator reading statistics), so the port carries no authorization of its own.
 */
public interface StudentCohorts {

    /** For each program with students actively in it, how many. */
    Map<UUID, Integer> activeStudentsByProgram(UUID tenantId);

    /** The program each of these students is actively in; a student with none is absent. */
    Map<UUID, UUID> activeProgramOf(Collection<UUID> studentIds);
}
