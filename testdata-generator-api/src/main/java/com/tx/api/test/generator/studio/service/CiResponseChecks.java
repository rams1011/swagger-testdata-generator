package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import io.restassured.module.jsv.JsonSchemaValidator;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.MatcherAssert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Schema and download checks the JDBC runner already applies, so Try and Run use the same result.
 */
final class CiResponseChecks {

    private static final Gson JSON = new Gson();
    private static final String NA = "NA";

    private CiResponseChecks() {
    }

    static AssertionCheck schema(Map<String, Object> schema, String body) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        String adapted = JsonSchemaSupport.forValidation(JSON.toJson(schema));
        try {
            MatcherAssert.assertThat(StringUtils.defaultString(body), JsonSchemaValidator.matchesJsonSchema(adapted));
            return new AssertionCheck("jsonSchema", null, "response schema", "matches", true);
        } catch (AssertionError error) {
            return new AssertionCheck("jsonSchema", null, "response schema",
                    shorten(StringUtils.defaultIfBlank(error.getMessage(), "schema mismatch")), false);
        }
    }

    static AssertionCheck download(String responseType, String expectedResponse, byte[] bytes, String savedPath) {
        if (!"FILE".equalsIgnoreCase(StringUtils.trimToEmpty(responseType))) {
            return null;
        }
        if (bytes == null || bytes.length == 0 || StringUtils.isBlank(savedPath) || !Files.isRegularFile(Path.of(savedPath))) {
            String actual = bytes != null && bytes.length == 0 ? "empty" : "not created";
            return new AssertionCheck("download", null, "non-empty file", actual, false);
        }
        if (!hasValue(expectedResponse)) {
            return new AssertionCheck("download", null, "non-empty file", bytes.length + " bytes", true);
        }
        String stripped = expectedResponse.trim();
        if (stripped.startsWith("file:")) {
            stripped = stripped.substring("file:".length());
        }
        Path expected = Path.of(stripped);
        if (!expected.isAbsolute()) {
            expected = Path.of(System.getProperty("user.dir")).resolve(expected).normalize();
        }
        if (!Files.isRegularFile(expected)) {
            return new AssertionCheck("download", null, expected.toString(), "expected file does not exist", false);
        }
        try {
            boolean match = Files.mismatch(Path.of(savedPath), expected) == -1L;
            return new AssertionCheck("download", null, expected.toString(), savedPath, match);
        } catch (IOException exception) {
            return new AssertionCheck("download", null, expected.toString(),
                    StringUtils.defaultIfBlank(exception.getMessage(), "compare failed"), false);
        }
    }

    private static String shorten(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500) + "…";
    }

    private static boolean hasValue(String value) {
        return StringUtils.isNotBlank(value) && !NA.equalsIgnoreCase(value.trim());
    }
}
