package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.model.StudioRequestFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestFileSupportTest {

    @Test
    void multipartWithoutFileThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RequestFileSupport.buildMultipart(Map.of(), "case-1", null));
        assertTrue(error.getMessage().contains("multipart file"));
    }

    @Test
    void filenameStringIsNotAFilePart() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", "resume.pdf");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RequestFileSupport.buildMultipart(body, "case-1", null));
        assertTrue(error.getMessage().contains("multipart file"));
    }

    @Test
    void putFileRefIsDetectedAsAFilePart() {
        StudioRequestFile file = stored("f1", "case-1", "resume.pdf", "hello");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) RequestFileSupport.putFileRef(Map.of(), "file", file);
        assertTrue(RequestFileSupport.hasFileRefs(body));
        assertEquals("f1", ((Map<?, ?>) body.get("file")).get("fileId"));
    }

    @Test
    void storedFileIsAttachedWhenBodyHasNoRef() {
        StudioRequestFile file = stored("f1", "case-1", "resume.pdf", "hello");
        RequestFileSupport.MultipartPayload payload =
                RequestFileSupport.buildMultipart(Map.of(), "case-1", null, List.of(file));
        String text = new String(payload.body(), StandardCharsets.UTF_8);
        assertTrue(text.contains("name=\"file\""));
        assertTrue(text.contains("filename=\"resume.pdf\""));
        assertTrue(text.contains("hello"));
    }

    @Test
    void storedFileUsesSchemaFieldName() {
        StudioRequestFile file = stored("f1", "case-1", "photo.png", "img");
        RequestFileSupport.MultipartPayload payload =
                RequestFileSupport.buildMultipart(Map.of("file", "photo.png"), "case-1", null, List.of(file));
        String text = new String(payload.body(), StandardCharsets.UTF_8);
        assertTrue(text.contains("name=\"file\""));
        assertTrue(text.contains("filename=\"photo.png\""));
    }

    private static StudioRequestFile stored(String fileId, String caseId, String name, String content) {
        StudioRequestFile file = new StudioRequestFile();
        file.setFileId(fileId);
        file.setTestCaseId(caseId);
        file.setFileName(name);
        file.setContentType("application/octet-stream");
        file.setContent(content.getBytes(StandardCharsets.UTF_8));
        file.setSizeBytes(file.getContent().length);
        return file;
    }
}
