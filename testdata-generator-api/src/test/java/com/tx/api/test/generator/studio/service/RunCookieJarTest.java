package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCookieJarTest {

    private static final long NOW = 1_000_000L;

    @Test
    void aCookieSetByOneResponseGoesWithLaterRequestsToTheSameSite() {
        Map<String, String> run = new LinkedHashMap<>();
        RunCookieJar.remember(run, "https://api.example.com/auth/login", List.of("SESSION=abc; Path=/; HttpOnly"), NOW);

        assertEquals(Map.of("SESSION", "abc"), RunCookieJar.cookiesFor(run, "https://api.example.com/users/1", NOW));
        assertTrue(RunCookieJar.cookiesFor(run, "https://other.example.com/users", NOW).isEmpty(), "host-only cookie");
        // the jar can't be reached as a {{variable}}
        assertTrue(run.keySet().stream().allMatch(key -> key.startsWith("\u0000")));
    }

    @Test
    void followsDomainPathSecureAndExpiryRules() {
        Map<String, String> run = new LinkedHashMap<>();
        RunCookieJar.remember(run, "https://api.example.com/a/login", List.of(
                "wide=1; Domain=example.com; Path=/",
                "scoped=2",
                "secure=3; Secure; Path=/",
                "short=4; Max-Age=60; Path=/",
                "foreign=5; Domain=elsewhere.com"), NOW);

        Map<String, String> sub = RunCookieJar.cookiesFor(run, "https://www.example.com/", NOW);
        assertEquals("1", sub.get("wide"));
        assertNull(sub.get("scoped"), "no Domain means this host only");
        assertEquals("2", RunCookieJar.cookiesFor(run, "https://api.example.com/a/b", NOW).get("scoped"));
        assertNull(RunCookieJar.cookiesFor(run, "https://api.example.com/other", NOW).get("scoped"), "default path /a");
        assertNull(RunCookieJar.cookiesFor(run, "http://api.example.com/", NOW).get("secure"), "Secure needs https");
        assertNull(RunCookieJar.cookiesFor(run, "https://api.example.com/", NOW + 61_000).get("short"), "expired");
        assertTrue(RunCookieJar.cookiesFor(run, "https://elsewhere.com/", NOW).isEmpty(), "can't set another site's");
    }

    @Test
    void aLaterResponseReplacesOrRemovesACookie() {
        Map<String, String> run = new LinkedHashMap<>();
        RunCookieJar.remember(run, "http://localhost:8080/login", List.of("token=old; Path=/"), NOW);
        RunCookieJar.remember(run, "http://localhost:8080/refresh", List.of("token=new; Path=/"), NOW);
        assertEquals(Map.of("token", "new"), RunCookieJar.cookiesFor(run, "http://localhost:8080/x", NOW));

        RunCookieJar.remember(run, "http://localhost:8080/logout", List.of("token=; Max-Age=0; Path=/"), NOW);
        assertTrue(RunCookieJar.cookiesFor(run, "http://localhost:8080/x", NOW).isEmpty());
    }

    @Test
    void theCasesOwnCookiesWinByName() {
        assertEquals("theme=dark; SESSION=mine; lang=en",
                RunCookieJar.merge("theme=dark; SESSION=mine", new LinkedHashMap<>(Map.of("SESSION", "jar", "lang", "en"))));
        assertEquals("SESSION=jar", RunCookieJar.merge(null, Map.of("SESSION", "jar")));
        assertNull(RunCookieJar.merge(null, Map.of()));
        assertFalse(RunCookieJar.merge("a=1", Map.of("a", "2")).contains("a=2"));
    }
}
