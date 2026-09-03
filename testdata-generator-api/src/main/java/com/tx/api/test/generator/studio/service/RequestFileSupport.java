package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

final class RequestFileSupport {

    private static final String NA = "NA";
    private static final Gson JSON = new Gson();

    private RequestFileSupport() {
    }

    static boolean isMultipartContentType(String contentType) {
        if (StringUtils.isBlank(contentType) || NA.equalsIgnoreCase(contentType.trim())) {
            return false;
        }
        String value = contentType.trim().toUpperCase(Locale.ROOT);
        return "MULTIPART".equals(value) || value.contains("MULTIPART");
    }

    static boolean hasFileRefs(Object body) {
        return !collectFileParts(body).isEmpty();
    }

    static List<FilePart> collectFileParts(Object body) {
        List<FilePart> parts = new ArrayList<>();
        if (isFileRef(body)) {
            parts.add(new FilePart("file", fileId(body)));
            return parts;
        }
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (isFileRef(value) && key != null) {
                    parts.add(new FilePart(String.valueOf(key), fileId(value)));
                }
            });
        }
        return parts;
    }

    static List<String> fileIds(Object body) {
        return collectFileParts(body).stream().map(FilePart::fileId).distinct().toList();
    }

    @SuppressWarnings("unchecked")
    static void stampTestCaseId(Object body, String testCaseId) {
        if (StringUtils.isBlank(testCaseId) || !(body instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> writable = (Map<String, Object>) map;
        if (isFileRef(writable)) {
            writable.put("testCaseId", testCaseId);
            return;
        }
        for (Object value : writable.values()) {
            if (value instanceof Map<?, ?> child && isFileRef(child)) {
                ((Map<String, Object>) child).put("testCaseId", testCaseId);
            }
        }
    }

    static Object stripFileRefs(Object body) {
        if (body == null || isFileRef(body)) {
            return null;
        }
        if (body instanceof Map<?, ?> map) {
            Map<String, Object> next = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key == null || isFileRef(value) || value == null) {
                    return;
                }
                next.put(String.valueOf(key), value);
            });
            return next;
        }
        return body;
    }

    static MultipartPayload buildMultipart(Object body, String testCaseId, StudioStorage storage) {
        String boundary = "----StudioBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (body instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String field = String.valueOf(entry.getKey());
                    Object value = entry.getValue();
                    if (isFileRef(value)) {
                        StudioRequestFile file = requireFile(caseIdOf(value, testCaseId), fileId(value), storage);
                        writeFilePart(out, boundary, field, file);
                    } else {
                        writeTextPart(out, boundary, field, textFieldValue(value));
                    }
                }
            } else if (isFileRef(body)) {
                StudioRequestFile file = requireFile(caseIdOf(body, testCaseId), fileId(body), storage);
                writeFilePart(out, boundary, "file", file);
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to build multipart request", exception);
        }
        return new MultipartPayload(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    static String sanitizeFileName(String name) {
        String base = StringUtils.defaultIfBlank(name, "upload.bin").replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            return "upload.bin";
        }
        return base;
    }

    static String textFieldValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return JSON.toJson(value);
    }

    private static String caseIdOf(Object value, String fallback) {
        if (value instanceof Map<?, ?> map) {
            Object id = map.get("testCaseId");
            if (id instanceof String text && StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return fallback;
    }

    private static StudioRequestFile requireFile(String testCaseId, String fileId, StudioStorage storage) {
        if (StringUtils.isBlank(testCaseId)) {
            throw new IllegalArgumentException("Save the test case before sending an uploaded file");
        }
        return storage.findRequestFile(testCaseId, fileId)
                .orElseThrow(() -> new NoSuchElementException("Uploaded file not found: " + fileId));
    }

    private static void writeTextPart(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + escape(name) + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary, String name, StudioRequestFile file)
            throws IOException {
        String fileName = sanitizeFileName(file.getFileName());
        String contentType = StringUtils.defaultIfBlank(file.getContentType(), "application/octet-stream");
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + escape(name)
                + "\"; filename=\"" + escape(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        if (file.getContent() != null) {
            out.write(file.getContent());
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return StringUtils.defaultString(value).replace("\"", "'");
    }

    static boolean isFileRef(Object value) {
        return StringUtils.isNotBlank(fileId(value));
    }

    private static String fileId(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object fileId = map.get("fileId");
        return fileId instanceof String text && StringUtils.isNotBlank(text) ? text : null;
    }

    record FilePart(String fieldName, String fileId) {
    }

    record MultipartPayload(byte[] body, String contentType) {
    }
}
