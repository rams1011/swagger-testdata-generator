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
