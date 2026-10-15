package com.setu.shared.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Announces this process to the gateway (see {@link ServiceRegistry}): writes its key at start-up, renews it every few seconds, removes it
 * on a clean shutdown. A failed write is logged and retried on the next beat; the process keeps serving, the gateway just may not know about
 * it until a write succeeds. Active only when {@code app.discovery.advertise-url} names the URL other processes reach this one on, so the
 * monolith and local test runs register nothing.
 */
@Component
@ConditionalOnProperty(name = "app.discovery.advertise-url")
public class ServiceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrar.class);

    private final StringRedisTemplate redis;
    private final String key;
    private final String url;

    public ServiceRegistrar(StringRedisTemplate redis, @Value("${app.discovery.service}") String service,
                            @Value("${app.discovery.advertise-url}") String url,
                            @Value("${app.discovery.instance-id:}") String instanceId) {
        this.redis = redis;
        this.url = url;
        this.key = ServiceRegistry.keyFor(service, instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId);
    }

    @PostConstruct
    void registerAtStartUp() {
        heartbeat();
    }

    @Scheduled(fixedRateString = "#{T(com.setu.shared.discovery.ServiceRegistry).HEARTBEAT.toMillis()}")
    public void heartbeat() {
        try {
            redis.opsForValue().set(key, url, ServiceRegistry.TTL);
        } catch (RuntimeException e) {
            log.warn("could not register {} with the gateway's discovery ({}); will retry", url, e.toString());
        }
    }

    @PreDestroy
    public void deregister() {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.debug("could not deregister {} on shutdown ({}); its key expires on its own", url, e.toString());
        }
    }
}
