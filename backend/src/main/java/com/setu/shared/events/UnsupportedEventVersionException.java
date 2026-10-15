package com.setu.shared.events;

/** An event (or its envelope) is newer than the consumer understands. Never retried; dead-lettered to wait for an upgraded consumer. See {@link EventVersions}. */
public class UnsupportedEventVersionException extends RuntimeException {

    public UnsupportedEventVersionException(String message) {
        super(message);
    }
}
