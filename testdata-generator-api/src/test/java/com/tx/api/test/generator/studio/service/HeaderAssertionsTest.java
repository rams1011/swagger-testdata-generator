package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderAssertionsTest {

    @Test
    void contentTypeMatchesTheMediaTypeAndAMissingHeaderFails() {
        Map<String, List<String>> headers = Map.of(
                "Content-Type", List.of("application/json;charset=UTF-8"),
                "Location", List.of("/orders/9"),
                "Set-Cookie", List.of("session=abc; Path=/; HttpOnly"));

        var checks = HeaderAssertions.checks(
                "header:Content-Type=application/json|header:Location=|cookie:session=abc|$.id=1",
                headers);

        assertEquals(3, checks.size());
        assertTrue(checks.get(0).passed());
        assertEquals("header", checks.get(0).name());
        assertTrue(checks.get(1).passed());
        assertTrue(checks.get(2).passed());
        assertTrue(HeaderAssertions.checks("header:ETag=abc", headers).get(0).actual().equals("(missing)"));
        assertFalse(HeaderAssertions.checks("header:ETag=abc", headers).get(0).passed());
    }
}
