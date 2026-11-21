package com.tx.api.test.generator.studio.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Adds {@code severity} to structured (JSON) log lines. Google Cloud Logging reads it from a Cloud Run container's
 * stdout as the entry's severity (otherwise every line is DEFAULT), so sink filters such as {@code severity>=INFO}
 * work. Elastic keeps ECS {@code log.level}.
 */
public class CloudLoggingSeverity implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add("severity", event -> severity(event.getLevel()));
    }

    static String severity(Level level) {
        if (level == null) {
            return "DEFAULT";
        }
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARNING";
            case Level.INFO_INT -> "INFO";
            case Level.DEBUG_INT, Level.TRACE_INT -> "DEBUG";
            default -> "DEFAULT";
        };
    }
}
