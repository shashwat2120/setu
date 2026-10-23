package com.setu.shared.attendancefacts;

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
 * Switches a process from reading attendance in-process to asking the Attendance service: set {@code setu.attendance.url} (the core does, once Attendance has a database
 * of its own). Without it the attendance module's own {@code LocalAttendanceCounts} answers.
 */
@Configuration(proxyBeanMethods = false)
class AttendanceCountsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "setu.attendance.url")
    AttendanceCounts remoteAttendanceCounts(@Value("${setu.attendance.url}") String url,
                                            @Value("${setu.internal.token:}") String token,
                                            @Value("${setu.attendance.connect-timeout-ms:500}") long connectMs,
                                            @Value("${setu.attendance.response-timeout-ms:2000}") long responseMs,
                                            @Value("${setu.attendance.max-concurrent:20}") int maxConcurrent,
                                            ObjectMapper mapper, MeterRegistry meters, Outbound outbound) {
        var settings = new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), maxConcurrent, Duration.ZERO);
        // a transport failure or an unexpected answer is the dependency failing; nothing else is ever thrown by the call
        var guard = new GuardedCall("attendance-counts", settings, e -> true, meters);
        return new RemoteAttendanceCounts(url, token, Duration.ofMillis(connectMs), Duration.ofMillis(responseMs), mapper, guard, outbound);
    }
}
