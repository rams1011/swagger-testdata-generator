package com.tx.api.test.generator.studio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StudioTestCase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The copy of a case kept each time it is published. */
public final class CaseVersionDocument {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Changed straight from the table or by removing a case from an environment, and not part of what gets restored. */
    private static final Set<String> SETTINGS = Set.of("enabled", "environments");

    private CaseVersionDocument() {
    }

    public static Map<String, Object> of(StudioTestCase testCase) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("testName", testCase.getTestName());
        document.put("method", testCase.getMethod());
        document.put("rawPath", testCase.getRawPath());
        document.put("endpoint", testCase.getEndpoint());
        document.put("baseUrl", testCase.getBaseUrl());
        document.put("contentType", testCase.getContentType());
        document.put("pathParams", testCase.getPathParams());
        document.put("queryParams", testCase.getQueryParams());
        document.put("headers", testCase.getHeaders());
        document.put("cookieParams", testCase.getCookieParams());
        document.put("requestBody", testCase.getRequestBody());
        document.put("statusCode", testCase.getStatusCode());
        document.put("responseType", testCase.getResponseType());
        document.put("expectedResponse", testCase.getExpectedResponse());
        document.put("responseMatch", testCase.getResponseMatch());
        document.put("ignoreFields", testCase.getIgnoreFields());
        document.put("auth", CaseAuth.toJson(testCase.getAuth()));
        document.put("enabled", testCase.getEnabled());
        document.put("testGroup", testCase.getTestGroup());
        document.put("jsonPathAssertions", testCase.getJsonPathAssertions());
        document.put("extracts", testCase.getExtracts());
        document.put("maxResponseTimeMs", testCase.getMaxResponseTimeMs());
        document.put("environments", testCase.getEnvironments());
        return document;
    }

    static Map<String, Object> read(String documentJson) {
        try {
            return JSON.readValue(documentJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("This version could not be read", exception);
        }
    }

    /** What the screen shows: the same document, with its auth as an object. */
    static Map<String, Object> forView(Map<String, Object> document) {
        Map<String, Object> view = new LinkedHashMap<>(document);
        view.put("auth", auth(document));
        return view;
    }

    /** Whether two documents differ in anything but {@link #SETTINGS}. */
    static boolean sameContent(Map<String, Object> left, Map<String, Object> right) {
        return content(left).equals(content(right));
    }

    private static Map<String, Object> content(Map<String, Object> document) {
        Map<String, Object> content = new TreeMap<>(document);
        content.keySet().removeAll(SETTINGS);
        return comparable(content);
    }

    /** A number read back from storage can be 1.0 where the copy has 1, and an empty field can be null or empty. */
    @SuppressWarnings("unchecked")
    private static <T> T comparable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> {
                Object kept = comparable(item);
                if (kept != null) {
                    result.put(String.valueOf(key), kept);
                }
            });
            return (T) (result.isEmpty() ? null : result);
        }
        if (value instanceof Collection<?> items) {
            List<Object> result = new ArrayList<>();
            items.forEach(item -> result.add(comparable(item)));
            return (T) (result.isEmpty() ? null : result);
        }
        if (value instanceof Number number) {
            return (T) new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return (T) ("".equals(value) ? null : value);
    }

    /**
     * Publishes the document's content over the case. Saving treats a missing response match, ignore fields or auth
     * as "leave it", so these say "none" when the version had none.
     */
    static SaveTestDataRequest toRequest(Map<String, Object> document, StudioTestCase current, Long expectedRevision,
                                         Long expectedDraftRevision, boolean force) {
        RunAuth auth = auth(document);
        return new SaveTestDataRequest(
                current.getSpecId(),
                current.getPlanId(),
                current.getOperationKey(),
                text(document, "testName"),
                text(document, "baseUrl"),
                textMap(document, "pathParams"),
                textMap(document, "queryParams"),
                textMap(document, "headers"),
                textMap(document, "cookieParams"),
                document.get("requestBody"),
                document.get("statusCode") instanceof Number status ? status.intValue() : null,
                text(document, "responseType"),
                text(document, "expectedResponse"),
                document.get("responseMatch") == null ? "strict" : text(document, "responseMatch"),
                document.get("ignoreFields") == null ? "" : text(document, "ignoreFields"),
                auth == null ? new RunAuth(CaseAuth.INHERIT, null, null, null, null, null, null, null, null, null, null, null) : auth,
                current.getEnabled(),
                CaseLifecycle.READY,
                text(document, "testGroup"),
                text(document, "jsonPathAssertions"),
                text(document, "extracts"),
                document.get("maxResponseTimeMs") instanceof Number limit ? limit.longValue() : null,
                text(document, "contentType"),
                null,
                current.getEnvironments(),
                expectedRevision,
                force,
                expectedDraftRevision,
                null);
    }

    private static RunAuth auth(Map<String, Object> document) {
        return document.get("auth") instanceof String json ? CaseAuth.fromJson(json) : null;
    }

    private static String text(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, String> textMap(Map<String, Object> document, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        if (document.get(key) instanceof Map<?, ?> map) {
            map.forEach((name, value) -> result.put(String.valueOf(name), value == null ? null : String.valueOf(value)));
        }
        return result;
    }
}
