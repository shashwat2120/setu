package com.setu.shared.discovery;

import java.time.Duration;

/**
 * The shared shape of Setu's service discovery, so a service that registers and the gateway that looks it up cannot disagree: one Redis
 * key per live instance, {@code setu:services:<service>:<instanceId>}, whose value is the instance's base URL and whose time to live is
 * {@link #TTL}. A service renews its key every {@link #HEARTBEAT}; when it stops (crash, network partition, shutdown) the key expires and
 * the instance drops out of the set, which is the health awareness: no probe is needed to learn that a process is gone.
 * <p>
 * Redis is already part of the platform and is a cache here, not a source of truth: losing it only makes the gateway fall back to the
 * instances listed in its configuration.
 */
public final class ServiceRegistry {

    public static final String KEY_PREFIX = "setu:services:";
    public static final Duration TTL = Duration.ofSeconds(30);
    public static final Duration HEARTBEAT = Duration.ofSeconds(10);

    private ServiceRegistry() {
    }

    public static String keyFor(String service, String instanceId) {
        return KEY_PREFIX + service + ":" + instanceId;
    }

    public static String patternFor(String service) {
        return KEY_PREFIX + service + ":*";
    }
}
