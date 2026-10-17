package com.setu.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Connects the Logback appender declared in {@code logback-spring.xml} to the application's OpenTelemetry SDK. Spring Boot builds the SDK and its
 * log exporter, but leaves this last step to the application: until it is done the appender only buffers. Once installed, every log line becomes
 * an OpenTelemetry log record carrying the trace and span id of the thread that logged it, which the SDK sends to Loki over OTLP when
 * {@code OTEL_LOGS_EXPORT_ENABLED=true} and otherwise drops (docs/contracts/observability.md).
 */
@Component
@ConditionalOnClass(name = "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender")
class OpenTelemetryLogAppenderInstaller implements InitializingBean {

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    OpenTelemetryLogAppenderInstaller(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        if (sdk != null) {
            OpenTelemetryAppender.install(sdk);
        }
    }
}
