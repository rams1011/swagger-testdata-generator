package com.tx.api.test.generator.studio.safety;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The same cases as the UI's src/requestSafety.test.js (the Read / Write chip): keep both tables in step. */
class RequestClassifierTest {

    static Stream<Arguments> cases() {
        String form = "application/x-www-form-urlencoded";
        return Stream.of(
                Arguments.of("GET reads", "GET", "https://api.acme.com/users", Map.of(), null, null, false),
                Arguments.of("HEAD reads", "head", null, Map.of(), null, null, false),
                Arguments.of("OPTIONS reads", "OPTIONS", null, Map.of(), null, null, false),
                Arguments.of("POST writes", "POST", null, Map.of(), null, null, true),
                Arguments.of("PUT writes", "PUT", null, Map.of(), null, null, true),
                Arguments.of("PATCH writes", "PATCH", null, Map.of(), null, null, true),
                Arguments.of("DELETE writes", "DELETE", null, Map.of(), null, null, true),
                Arguments.of("TRACE writes", "TRACE", null, Map.of(), null, null, true),
                Arguments.of("a custom method writes", "PURGE", null, Map.of(), null, null, true),
                Arguments.of("a blank method is GET", "", null, Map.of(), null, null, false),
                Arguments.of("GET with X-HTTP-Method-Override: DELETE writes", "GET", null,
                        Map.of("x-http-method-override", "DELETE"), null, null, true),
                Arguments.of("GET with X-HTTP-Method: PUT writes", "GET", null, Map.of("X-HTTP-Method", "PUT"), null,
                        null, true),
                Arguments.of("GET with X-Method-Override: GET reads", "GET", null, Map.of("X-Method-Override", "GET"),
                        null, null, false),
                Arguments.of("GET with a blank override header reads", "GET", null,
                        Map.of("X-HTTP-Method-Override", " "), null, null, false),
                Arguments.of("GET with _method=DELETE in the query writes", "GET", "https://a.test/x?a=1&_method=DELETE",
                        Map.of(), null, null, true),
                Arguments.of("GET with _method=get in the query reads", "GET", "https://a.test/x?_method=get", Map.of(),
                        null, null, false),
                Arguments.of("GET with a _method form field writes", "GET", null, Map.of(), "a=1&_method=PATCH", form,
                        true),
                Arguments.of("GET with a _method JSON key writes", "GET", null, Map.of(), "{\"_method\":\"DELETE\"}",
                        "application/json", true),
                Arguments.of("GET with a _method multipart part writes", "GET", null, Map.of(),
                        "Content-Disposition: form-data; name=\"_method\"\r\n\r\nPUT", "multipart/form-data", true),
                Arguments.of("POST with an override asking for GET still writes", "POST", null,
                        Map.of("X-HTTP-Method-Override", "GET"), null, null, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classifies(String name, String method, String url, Map<String, String> headers, String body,
                    String contentType, boolean write) {
        assertEquals(write, RequestClassifier.classify(method, url, headers, body, contentType).write(), name);
    }
}
