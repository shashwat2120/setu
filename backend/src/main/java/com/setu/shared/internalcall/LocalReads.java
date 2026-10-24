package com.setu.shared.internalcall;

/**
 * A scope, on the current thread, inside which the data readers that normally ask another service ({@code AttendanceCounts}, {@code AssessmentFacts},
 * {@code LearningFacts}) answer from this process's own copy instead.
 * <p>
 * It exists for one moment: the first start of the core on an empty database. The core seeds the demo college (and recalculates risk for it) before it is healthy, and the
 * Attendance, Assessment and Learning services cannot start until it is healthy, because their databases are copied from what it seeded. Asking them then would wait for
 * something that is waiting for the asker. Inside the scope the data being asked about is exactly what the seeder has just written to the core's own database, so reading it
 * locally is correct, not a shortcut. Nothing else opens the scope; every request and every event handled after start-up goes to the owning service.
 * <p>
 * Thread-bound on purpose: work that moves to another thread is not inside the scope and goes to the services, which is the safe direction.
 */
public final class LocalReads {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private LocalReads() {
    }

    /** An open scope; closing it leaves the scope (scopes nest). */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope enter() {
        DEPTH.set(DEPTH.get() + 1);
        return () -> {
            int depth = DEPTH.get() - 1;
            if (depth <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        };
    }

    /** Whether the current thread is inside a scope. */
    public static boolean active() {
        return DEPTH.get() > 0;
    }
}
