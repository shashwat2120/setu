package com.setu.shared.events;

/**
 * The rule for events from the future. A consumer is written against the versions it knows. An event with a higher payload version than that,
 * or an envelope with a higher schema version, may mean anything; acting on it as if it were the old shape is how data is quietly corrupted.
 * So the consumer refuses it: {@link UnsupportedEventVersionException} is never retried (retrying cannot make it understandable) and the record
 * goes to the {@code .DLQ} topic, where it waits to be replayed once the consumer has been upgraded. The producer side of the rule: a breaking
 * payload change bumps {@code event_version}, and the consumers are deployed before the producer starts writing the new version.
 * Missing versions (events from before they were carried) are read as version 1.
 */
public final class EventVersions {

    private EventVersions() {
    }

    /**
     * @param supportedEventVersion the highest payload version this consumer understands for this event type
     * @throws UnsupportedEventVersionException if the event or its envelope is newer than the consumer
     */
    public static void assertSupported(String eventType, Integer eventVersion, Integer schemaVersion, int supportedEventVersion) {
        if (eventVersion != null && eventVersion > supportedEventVersion) {
            throw new UnsupportedEventVersionException(eventType + " version " + eventVersion + " is newer than this consumer understands (" + supportedEventVersion + ")");
        }
        if (schemaVersion != null && schemaVersion > EventEnvelope.SCHEMA_VERSION) {
            throw new UnsupportedEventVersionException(eventType + " uses envelope version " + schemaVersion + ", newer than this consumer understands ("
                + EventEnvelope.SCHEMA_VERSION + ")");
        }
    }

    /** Reads a numeric header; absent or unreadable means "not carried". */
    public static Integer parse(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Integer.valueOf(header.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
