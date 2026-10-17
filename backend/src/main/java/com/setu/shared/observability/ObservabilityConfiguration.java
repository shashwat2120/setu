package com.setu.shared.observability;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires {@link Outbound} to the application's observation registry, when it has one. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    Outbound outbound(ObjectProvider<ObservationRegistry> registry, TraceParents traceParents) {
        return new Outbound(registry.getIfAvailable(), traceParents);
    }
}
