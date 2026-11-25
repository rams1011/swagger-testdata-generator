package com.tx.api.test.generator.studio.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TESTDATA_LOG_FORMAT switches the console to JSON through application.yml, and the JSON line keeps the log context
 * (request, trace and workspace ids) that Elastic and Cloud Logging search on.
 */
class StructuredLoggingConfigTest {

    private static final String FORMAT = "logging.structured.format.console";

    @Test
    void consoleStaysReadableTextUnlessTheVariableIsSet() throws Exception {
        assertEquals("", environment(Map.of()).getProperty(FORMAT));
    }

    @Test
    void aLogFileForShippingIsWrittenOnlyWhenNamedAndIsEcsJson() throws Exception {
        StandardEnvironment off = environment(Map.of());
        assertEquals("", off.getProperty("logging.file.name"), "no file unless TESTDATA_LOG_FILE is set");

        StandardEnvironment on = environment(Map.of("TESTDATA_LOG_FILE", "D:/logs/testdata-studio.json"));
        assertEquals("D:/logs/testdata-studio.json", on.getProperty("logging.file.name"));
        assertEquals("ecs", on.getProperty("logging.structured.format.file"));
        assertEquals("", on.getProperty(FORMAT), "the console keeps its readable format");
        assertEquals("50MB", on.getProperty("logging.logback.rollingpolicy.max-file-size"));
        assertEquals("7", on.getProperty("logging.logback.rollingpolicy.max-history"));
    }

    @Test
    void ecsLinesCarryTheLogContext() throws Exception {
        StandardEnvironment environment = environment(Map.of(
                "TESTDATA_LOG_FORMAT", "ecs", "TESTDATA_LOG_ENVIRONMENT", "cloud"));
        assertEquals("ecs", environment.getProperty(FORMAT));

        JsonNode line = encode(environment, Level.WARN, Map.of(
                RequestCorrelationFilter.MDC_KEY, "req-12345678",
                "tenantId", "ws-acme",
                "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                "spanId", "00f067aa0ba902b7",
                "studioReadSource", "redis"));

        assertEquals("WARN", line.at("/log/level").asText());
        assertEquals("Spec catalog is slow", line.at("/message").asText());
        assertEquals("testdata-studio", line.at("/service/name").asText());
        assertEquals("cloud", line.at("/service/environment").asText());
        assertEquals("req-12345678", line.at("/requestId").asText());
        assertEquals("ws-acme", line.at("/tenantId").asText());
        assertEquals("redis", line.at("/studioReadSource").asText());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", line.path("trace.id").asText(), line.toString());
        assertEquals("00f067aa0ba902b7", line.path("span.id").asText());
        // Dotted names: Elasticsearch maps "trace.id" the same as {"trace":{"id":...}}.
        assertFalse(line.has("traceId"));
        assertEquals("WARNING", line.at("/severity").asText());
        assertTrue(line.has("@timestamp"));
    }

    @Test
    void severityUsesCloudLoggingNames() {
        assertEquals("ERROR", CloudLoggingSeverity.severity(Level.ERROR));
        assertEquals("WARNING", CloudLoggingSeverity.severity(Level.WARN));
        assertEquals("INFO", CloudLoggingSeverity.severity(Level.INFO));
        assertEquals("DEBUG", CloudLoggingSeverity.severity(Level.TRACE));
        assertEquals("DEFAULT", CloudLoggingSeverity.severity(null));
    }

    private static JsonNode encode(Environment environment, Level level, Map<String, String> mdc) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(environment.getProperty(FORMAT));
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        try {
            LoggingEvent event = new LoggingEvent(StructuredLoggingConfigTest.class.getName(),
                    context.getLogger("com.tx.api.test.generator.studio.Example"), level,
                    "Spec catalog is slow", null, null);
            event.setMDCPropertyMap(mdc);
            String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
            return new ObjectMapper().readTree(json);
        } finally {
            encoder.stop();
        }
    }

    /** application.yml over the given system environment, as the app reads them at start-up. */
    private static StandardEnvironment environment(Map<String, Object> variables) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yaml.forEach(sources::addLast);
        return environment;
    }
}
