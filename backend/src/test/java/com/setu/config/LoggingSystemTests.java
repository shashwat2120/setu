package com.setu.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the application logs by default: the console and the OpenTelemetry appender, and no file. The configuration once included Spring Boot's
 * {@code base.xml}, which always attaches a rolling {@code spring.log} in the system temp directory whether or not a log file was asked for; that
 * wrote a file nobody configured, in production as well, and its rolling policy's clean-up job timing out at start-up failed application contexts
 * at random on a slow disk. A log file is opt-in now (the {@code file-logs} profile, see logback-spring.xml).
 */
@SpringBootTest
class LoggingSystemTests {

    @Test
    void logsGoToTheConsoleAndOpenTelemetryAndNoFileIsWrittenUnlessAskedFor() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        assertThat(factory).isInstanceOf(LoggerContext.class);
        Logger root = ((LoggerContext) factory).getLogger(Logger.ROOT_LOGGER_NAME);

        List<Appender<?>> appenders = new ArrayList<>();
        for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            appenders.add(it.next());
        }

        assertThat(appenders).extracting(Appender::getName).contains("CONSOLE", "OTEL");
        assertThat(appenders).as("no file appender unless the file-logs profile is on").noneMatch(a -> a instanceof FileAppender);
    }
}
