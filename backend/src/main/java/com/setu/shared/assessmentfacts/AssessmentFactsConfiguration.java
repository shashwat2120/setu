package com.setu.shared.assessmentfacts;

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
 * Switches a process from reading assessment data in-process to asking the Assessment service: set {@code setu.assessment.url} (the core does, once Assessment has a database
 * of its own). Without it the assessment module's own {@code LocalAssessmentFacts} answers.
 */
@Configuration(proxyBeanMethods = false)
class AssessmentFactsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "setu.assessment.url")
    RemoteAssessmentFacts remoteAssessmentFacts(@Value("${setu.assessment.url}") String url,
                                          @Value("${setu.internal.token:}") String token,
                                          @Value("${setu.assessment.connect-timeout-ms:500}") long connectMs,
                                          @Value("${setu.assessment.response-timeout-ms:2000}") long responseMs,
                                          @Value("${setu.assessment.max-concurrent:20}") int maxConcurrent,
                                          ObjectMapper mapper, MeterRegistry meters, Outbound outbound) {
        var settings = new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), maxConcurrent, Duration.ZERO);
        // a transport failure or an unexpected answer is the dependency failing; nothing else is ever thrown by the call
        var guard = new GuardedCall("assessment-facts", settings, e -> true, meters);
        return new RemoteAssessmentFacts(url, token, Duration.ofMillis(connectMs), Duration.ofMillis(responseMs), mapper, guard, outbound);
    }
}
