package com.tx.api.test.generator.studio.safety;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetPatternsTest {

    @Test
    void hostsArePatternsOrRanges() {
        assertEquals(new TargetPatterns.Parsed(TargetKind.HOST, "api.acme.com"), TargetPatterns.parse(" API.Acme.com. "));
        assertEquals(new TargetPatterns.Parsed(TargetKind.HOST, "*.prod.acme.com"), TargetPatterns.parse("*.prod.acme.com"));
        assertEquals(new TargetPatterns.Parsed(TargetKind.HOST, "xn--bcher-kva.example"), TargetPatterns.parse("bücher.example"));
        assertEquals(new TargetPatterns.Parsed(TargetKind.CIDR, "10.20.0.0/16"), TargetPatterns.parse("10.20.7.9/16"));
        assertEquals(new TargetPatterns.Parsed(TargetKind.CIDR, "10.0.0.5/32"), TargetPatterns.parse("10.0.0.5"));
        assertEquals(TargetKind.CIDR, TargetPatterns.parse("2001:db8::/32").kind());
    }

    @Test
    void unclearTargetsAreRefusedWithAMessage() {
        for (String bad : List.of("", "*", "*.com", "api.*.com", "prod*.acme.com", "https://api.acme.com",
                "api.acme.com/v1", "api.acme.com:443", "-bad-.acme.com", "10.0.0.0/33", "999.1.1.1", "0.0.0.0/0",
                "10.0.0.0/x")) {
            assertThrows(IllegalArgumentException.class, () -> TargetPatterns.parse(bad), bad);
        }
    }

    @Test
    void portsAreSortedAndChecked() {
        assertEquals(List.of(443, 8443), TargetPatterns.normalizePorts(List.of(8443, 443, 443)));
        assertEquals(List.of(), TargetPatterns.normalizePorts(null));
        assertThrows(IllegalArgumentException.class, () -> TargetPatterns.normalizePorts(List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> TargetPatterns.normalizePorts(List.of(70000)));
    }

    @Test
    void aWildcardCoversHostsBelowItButNotItself() {
        assertTrue(TargetPatterns.hostMatches("*.prod.acme.com", "orders.prod.acme.com"));
        assertTrue(TargetPatterns.hostMatches("*.prod.acme.com", "a.b.prod.acme.com"));
        assertFalse(TargetPatterns.hostMatches("*.prod.acme.com", "prod.acme.com"));
        assertFalse(TargetPatterns.hostMatches("*.prod.acme.com", "evilprod.acme.com"));
        assertTrue(TargetPatterns.hostMatches("api.acme.com", "api.acme.com"));
        assertFalse(TargetPatterns.hostMatches("api.acme.com", "api.acme.com.evil.test"));
    }

    @Test
    void rangesMatchAddresses() throws Exception {
        assertTrue(TargetPatterns.cidrContains("10.20.0.0/16", InetAddress.getByName("10.20.255.1")));
        assertFalse(TargetPatterns.cidrContains("10.20.0.0/16", InetAddress.getByName("10.21.0.1")));
        assertFalse(TargetPatterns.cidrContains("10.20.0.0/16", InetAddress.getByName("::1")));
    }

    @Test
    void urlsMatchByHostPortAndAddress() {
        List<ProtectedTarget> targets = List.of(
                target(TargetKind.HOST, "api.acme.com", List.of()),
                target(TargetKind.HOST, "*.prod.acme.com", List.of(443)),
                target(TargetKind.CIDR, "10.20.0.0/16", List.of()));
        assertEquals("api.acme.com", TargetPatterns.matchUrl(targets, "https://API.acme.com./v1").orElseThrow().pattern());
        assertEquals("*.prod.acme.com",
                TargetPatterns.matchUrl(targets, "https://orders.prod.acme.com").orElseThrow().pattern());
        assertTrue(TargetPatterns.matchUrl(targets, "http://orders.prod.acme.com").isEmpty(), "port 80 isn't listed");
        assertEquals("10.20.0.0/16", TargetPatterns.matchUrl(targets, "http://10.20.3.4:8080").orElseThrow().pattern());
        assertTrue(TargetPatterns.matchUrl(targets, "https://staging.acme.com").isEmpty());
        assertTrue(TargetPatterns.matchUrl(targets, "{{host}}/v1").isEmpty());
        assertTrue(TargetPatterns.matchUrl(targets, null).isEmpty());
    }

    private static ProtectedTarget target(TargetKind kind, String pattern, List<Integer> ports) {
        return new ProtectedTarget(pattern, kind, pattern, ports, null, null, Instant.now());
    }
}
