package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiResponseChecksTest {

    @TempDir
    Path temp;

    @Test
    void schemaAcceptsAMatchingBodyAndRejectsAMissingField() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "string")));

        assertTrue(CiResponseChecks.schema(schema, "{\"id\":\"a\"}").passed());
        assertFalse(CiResponseChecks.schema(schema, "{}").passed());
        assertNull(CiResponseChecks.schema(Map.of(), "{\"id\":\"a\"}"));
    }

    @Test
    void aSchemaReadBackWithDecimalCountsStillValidates() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("phones", Map.of("type", "array", "minItems", 1.0, "maxItems", 3.0),
                        "name", Map.of("type", "string", "maxLength", 10.0)));

        assertTrue(CiResponseChecks.schema(schema, "{\"phones\":[1],\"name\":\"Mira\"}").passed(),
                "minItems 1.0 is read as 1, not refused as an invalid schema");
        assertFalse(CiResponseChecks.schema(schema, "{\"phones\":[]}").passed());
    }

    @Test
    void downloadComparesBytesAndRejectsAnEmptyFile() throws Exception {
        Path expected = temp.resolve("expected.bin");
        Path saved = temp.resolve("saved.bin");
        Files.write(expected, new byte[] {1, 2, 3});
        Files.write(saved, new byte[] {1, 2, 3});

        assertTrue(CiResponseChecks.download("FILE", expected.toString(), new byte[] {1, 2, 3}, saved.toString()).passed());
        Files.write(saved, new byte[] {9});
        assertFalse(CiResponseChecks.download("FILE", expected.toString(), new byte[] {9}, saved.toString()).passed());
        assertFalse(CiResponseChecks.download("FILE", "NA", new byte[0], saved.toString()).passed());
        assertNull(CiResponseChecks.download("JSON", expected.toString(), new byte[] {1}, saved.toString()));
    }
}
