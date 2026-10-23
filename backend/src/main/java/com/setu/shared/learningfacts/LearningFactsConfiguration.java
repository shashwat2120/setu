package com.setu.shared.learningfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.observability.Outbound;
import com.setu.shared.resilience.GuardedCall;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Switches a process from reading learning data in-process to asking the Learning service: set {@code setu.learning.url} (the core does, once Learning has a database of its own).
 * Without it the learning module's own {@code LocalLearningFacts} answers.
 */
@Configuration(proxyBeanMethods = false)
class LearningFactsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "setu.learning.url")
    LearningFacts remoteLearningFacts(@Value("${setu.learning.url}") String url,
                                      @Value("${setu.internal.token:}") String token,
                                      @Value("${setu.learning.connect-timeout-ms:500}") long connectMs,
                                      @Value("${setu.learning.response-timeout-ms:2000}") long responseMs,
                                      @Value("${setu.learning.max-concurrent:20}") int maxConcurrent,
                                      ObjectMapper mapper, MeterRegistry meters, Outbound outbound) {
        var settings = new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), maxConcurrent, Duration.ZERO);
        // a transport failure or an unexpected answer is the dependency failing; nothing else is ever thrown by the call
        var guard = new GuardedCall("learning-facts", settings, e -> true, meters);
        return new RemoteLearningFacts(url, token, Duration.ofMillis(connectMs), Duration.ofMillis(responseMs), mapper, guard, outbound);
    }
}
