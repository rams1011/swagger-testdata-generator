package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundPrivateTargetPolicyTest {

    private static final String STAGING_ID = "11111111-1111-1111-1111-111111111111";
    private static final OutboundUrlPolicy.Scope STAGING = new OutboundUrlPolicy.Scope(STAGING_ID, "Staging");
    private static final OutboundUrlPolicy.Scope OTHER =
            new OutboundUrlPolicy.Scope("22222222-2222-2222-2222-222222222222", "Other");

    // Allowlist

    @Test
    void exactHostAndPortAreAllowedForTheirEnvironmentOnly() {
        OutboundUrlPolicy policy = policy(List.of("orders.internal:8443"), "orders.internal", "10.0.0.5");

        OutboundUrlPolicy.Target target = policy.check("https://orders.internal:8443/v1", STAGING);
        assertEquals("10.0.0.5", target.address().getHostAddress());
        assertEquals("orders.internal", target.host());
        assertEquals("orders.internal:8443", target.privateRule());

        assertBlocked(policy, "https://orders.internal:9000/v1", STAGING);
        assertBlocked(policy, "https://orders.internal:8443/v1", OTHER);
        assertBlocked(policy, "https://orders.internal:8443/v1", OutboundUrlPolicy.Scope.NONE);
    }

    @Test
    void anEntryNeedsAPortMatchIncludingTheSchemeDefault() {
        OutboundUrlPolicy policy = policy(List.of("orders.internal:443"), "orders.internal", "10.0.0.5");
        assertEquals("orders.internal:443", policy.check("https://orders.internal/v1", STAGING).privateRule());
        assertBlocked(policy, "http://orders.internal/v1", STAGING);
    }

    @Test
    void cidrEntriesCoverEveryAddressInTheRangeAndNoOthers() {
        OutboundUrlPolicy policy = policy(List.of("10.20.0.0/16:443", "[fd12:3456::/32]:443"));
        assertEquals("10.20.0.0/16:443", policy.check("https://10.20.255.1/", STAGING).privateRule());
        assertEquals("[fd12:3456::/32]:443", policy.check("https://[fd12:3456::1]/", STAGING).privateRule());
        assertBlocked(policy, "https://10.21.0.1/", STAGING);
        assertBlocked(policy, "https://10.20.0.1:8443/", STAGING);
        assertBlocked(policy, "https://[fd12:3457::1]/", STAGING);
    }

    @Test
    void aPublicTargetNeedsNoEntryAndIsNotMarkedPrivate() {
        OutboundUrlPolicy policy = policy(List.of(), "api.example.com", "93.184.216.34");
        OutboundUrlPolicy.Target target = policy.check("https://api.example.com/", STAGING);
        assertEquals("93.184.216.34", target.address().getHostAddress());
        assertNull(target.privateRule());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders.internal", "orders.internal:0", "orders.internal:99999", "*.internal:443",
            "10.0.0.0/4:443", "0.0.0.0/0:443", "10.0.0.0/33:443", "fd12::1:443", "[::/0]:443", "999.1.1.1:443",
            "[not-an-address]:443", ""})
    void badEntriesAreRefusedWhenTheServerStarts(String entry) {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, List.of(entry)));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OutboundUrlPolicy(properties));
        assertTrue(exception.getMessage().contains("Invalid private target allowlist entry"), exception.getMessage());
    }

    @Test
    void theDeprecatedFlagStillWorksAndDefaultsToOff() {
        StudioProperties properties = new StudioProperties();
        assertFalse(properties.getRun().isAllowPrivateNetworks());
        assertBlocked(new OutboundUrlPolicy(properties, null, host -> List.of()), "http://10.0.0.5/", STAGING);

        properties.getRun().setAllowPrivateNetworks(true);
        OutboundUrlPolicy.Target target = new OutboundUrlPolicy(properties, null, host -> List.of())
                .check("http://10.0.0.5/", STAGING);
        assertEquals(OutboundUrlPolicy.FALLBACK_RULE, target.privateRule());
    }

    @Test
    void anAllowlistEntryIsReportedAheadOfTheDeprecatedFlag() {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, List.of("10.0.0.5:80")));
        assertEquals("10.0.0.5:80",
                new OutboundUrlPolicy(properties, null, host -> List.of()).check("http://10.0.0.5/", STAGING).privateRule());
    }

    @Test
    void theAllowlistBindsFromConfigurationKeyedByEnvironmentId() {
        StudioProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "testdata.run.private-target-allowlist." + STAGING_ID + "[0]", "orders.internal:8443",
                "testdata.run.private-target-allowlist." + STAGING_ID + "[1]", "[fd12:3456::/48]:443",
                "testdata.run.blocked-infrastructure-ports", "6379,9999")))
                .bind("testdata", StudioProperties.class).get();
        assertEquals(Map.of(STAGING_ID, List.of("orders.internal:8443", "[fd12:3456::/48]:443")),
                properties.getRun().getPrivateTargetAllowlist());
        assertEquals(java.util.Set.of(6379, 9999), properties.getRun().blockedInfrastructurePortSet());
    }

    // Always blocked

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "http://169.254.170.2/",
            "http://100.100.100.200/",
            "http://[fd00:ec2::254]/",
            "http://metadata.google.internal/",
            "http://METADATA.google.internal./",
            "http://169.254.10.10/",
            "http://[fe80::1]/",
            "http://[febf::1]/",
    })
    void metadataAndLinkLocalAreBlockedEvenWhenAllowlistedOrOpenedByTheFlag(String url) {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, List.of(
                "169.254.0.0/16:80", "[fd00::/8]:80", "[fe80::/10]:80", "100.64.0.0/10:80",
                "metadata.google.internal:80")));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties, null, host -> List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy.check(url, STAGING));
        assertTrue(exception.getMessage().contains("cannot be added to the allowlist"), exception.getMessage());
    }

    @Test
    void aNameThatResolvesToMetadataIsBlockedEvenWhenTheNameIsAllowlisted() {
        OutboundUrlPolicy policy = policy(List.of("innocent.internal:80"), "innocent.internal", "169.254.169.254");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy.check("http://innocent.internal/", STAGING));
        assertTrue(exception.getMessage().contains("cannot be added to the allowlist"), exception.getMessage());
        assertFalse(exception.getMessage().contains("169.254"), exception.getMessage());
    }

    @Test
    void theServersOwnPortsAreBlockedOnLoopbackAndAnyLocal() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8083")
                .withProperty("management.server.port", "8084");
        StudioProperties properties = new StudioProperties();
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, List.of(
                "127.0.0.0/8:8083", "127.0.0.1:8084", "127.0.0.1:9000", "0.0.0.0:8083", "[::1]:8083")));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties, environment, host -> List.of());

        for (String url : List.of("http://127.0.0.1:8083/", "http://127.0.0.1:8084/actuator",
                "http://127.1.2.3:8083/", "http://0.0.0.0:8083/", "http://[::1]:8083/")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> policy.check(url, STAGING), url);
            assertTrue(exception.getMessage().contains("cannot be added to the allowlist"), exception.getMessage());
        }
        assertEquals("127.0.0.1:9000", policy.check("http://127.0.0.1:9000/", STAGING).privateRule());
    }

    @Test
    void infrastructurePortsAreBlockedOnPrivateAddressesAndAreConfigurable() {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setBlockedInfrastructurePorts("6379, 9999");
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, List.of(
                "10.0.0.5:6379", "10.0.0.5:9999", "10.0.0.5:5432")));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties, null, host -> List.of());

        assertThrows(IllegalArgumentException.class, () -> policy.check("http://10.0.0.5:6379/", STAGING));
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://10.0.0.5:9999/", STAGING));
        assertEquals("10.0.0.5:5432", policy.check("http://10.0.0.5:5432/", STAGING).privateRule());
        // A public host on the same port is someone else's service, not this network's infrastructure.
        assertNull(policy.check("http://93.184.216.34:6379/", STAGING).privateRule());
    }

    @Test
    void defaultInfrastructurePortsAreOnEvenWithTheDeprecatedFlag() {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties, null, host -> List.of());
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://127.0.0.1:2375/", STAGING));
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://10.0.0.5:10250/", STAGING));
    }

    // Unusual address formats

    @ParameterizedTest
    @ValueSource(strings = {
            "2852039166",               // decimal
            "0xA9FEA9FE",               // hex
            "0xa9.0xfe.0xa9.0xfe",      // dotted hex
            "0251.0376.0251.0376",      // dotted octal
            "025177524776",             // octal
            "169.254.43518",            // three parts
            "169.16689662",             // two parts
            "0xa9.254.0251.254",        // mixed
            "[::ffff:169.254.169.254]", // IPv4-mapped
            "[::ffff:a9fe:a9fe]",       // IPv4-mapped in hex
            "[0:0:0:0:0:ffff:a9fe:a9fe]",
            "[::169.254.169.254]",      // IPv4-compatible
            "[64:ff9b::a9fe:a9fe]",     // NAT64
            "[2002:a9fe:a9fe::1]",      // 6to4
    })
    void everySpellingOfTheMetadataAddressIsAlwaysBlocked(String host) {
        OutboundUrlPolicy policy = policy(List.of("169.254.0.0/16:80"));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy.check("http://" + host + "/", STAGING), host);
        assertTrue(exception.getMessage().contains("cannot be added to the allowlist"), exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2130706433", "0x7f000001", "017700000001", "0177.0.0.1", "0x7f.0.0.1", "127.1", "127.0.1",
            "0", "0x0", "0.0.0.0", "167772165", "0x0a000005", "012.0.0.5", "10.5", "[::ffff:10.0.0.5]",
            "[::ffff:7f00:1]", "[::]", "[::1]", "[fc00::1]", "[fdff::1]", "[fec0::1]", "[64:ff9b::a00:5]",
            "[2002:0a00:0005::]", "[ff02::1]"})
    void privateAddressesAreBlockedInEverySpelling(String host) {
        assertBlocked(policy(List.of()), "http://" + host + "/", STAGING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.4.5", "999.1.1.1", "256.0.0.1", "1.1.1.256", "4294967296", "0x100000000", "08.0.0.1",
            "1..1", "a.b.0x7f", "a.b.127", "-1.0.0.1"})
    void hostsThatLookNumericButAreNotAddressesAreBlockedWithoutADnsLookup(String host) {
        AtomicInteger lookups = new AtomicInteger();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new StudioProperties(), null, name -> {
            lookups.incrementAndGet();
            return List.of(InetAddress.getByName("93.184.216.34"));
        });
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://" + host + "/", STAGING), host);
        assertEquals(0, lookups.get(), host);
    }

    @Test
    void numericSpellingsAreParsedToTheAddressTheyMean() {
        assertAddress("127.0.0.1", OutboundUrlPolicy.parseIpv4("2130706433"));
        assertAddress("127.0.0.1", OutboundUrlPolicy.parseIpv4("0x7f.1"));
        assertAddress("127.0.0.1", OutboundUrlPolicy.parseIpv4("0177.0.1"));
        assertAddress("10.0.0.5", OutboundUrlPolicy.parseIpv4("012.0.0.05"));
        assertAddress("255.255.255.255", OutboundUrlPolicy.parseIpv4("4294967295"));
    }

    @Test
    void aSpelledOutAddressMatchesItsAllowlistEntryAndConnectsToTheRealAddress() {
        OutboundUrlPolicy policy = policy(List.of("10.0.0.5:80"));
        for (String host : List.of("167772165", "0x0a000005", "012.0.0.5", "[::ffff:10.0.0.5]")) {
            assertEquals("10.0.0.5", policy.check("http://" + host + "/", STAGING).address().getHostAddress(), host);
        }
    }

    @Test
    void aDottedSpellingThatUriCannotCarryIsRefusedEvenWhenItsAddressIsAllowlisted() {
        // java.net.URI reports no host for these, so nothing could connect to them.
        assertBlocked(policy(List.of("10.0.0.5:80")), "http://10.5/", STAGING);
        assertBlocked(policy(List.of("10.0.0.5:80")), "http://0xa.0.0.5/", STAGING);
    }

    // IPv6

    @Test
    void anIpv6AnswerInsideAnIpv4MappedWrapperIsUnwrapped() throws Exception {
        // Inet6Address.getByAddress keeps the mapped form, as some resolvers can return it.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 10;
        mapped[15] = 5;
        InetAddress wrapped = Inet6Address.getByAddress(null, mapped, -1);
        assertEquals("10.0.0.5", OutboundUrlPolicy.embeddedIpv4(wrapped).getHostAddress());
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new StudioProperties(), null, host -> List.of(wrapped));
        assertBlocked(policy, "http://wrapped.example/", STAGING);
    }

    @Test
    void aPublicIpv6AddressIsPinnedAndCarriesNoRule() {
        OutboundUrlPolicy policy = policy(List.of(), "v6.example.com", "2606:2800:220:1:248:1893:25c8:1946");
        OutboundUrlPolicy.Target target = policy.check("https://v6.example.com/", STAGING);
        assertTrue(target.address() instanceof Inet6Address);
        assertNull(target.privateRule());
        assertNull(policy.check("https://[2606:2800:220:1:248:1893:25c8:1946]/", STAGING).privateRule());
    }

    @Test
    void anIpv6ScopeIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> policy(List.of()).check("http://[fd12::1%25eth0]/", STAGING));
    }

    // DNS rebinding

    @Test
    void aHostResolvingToAPublicAndAPrivateAddressIsBlockedInEitherOrder() {
        assertBlocked(policy(List.of(), "rebind.example", "93.184.216.34", "10.0.0.5"),
                "https://rebind.example/", STAGING);
        assertBlocked(policy(List.of(), "rebind.example", "10.0.0.5", "93.184.216.34"),
                "https://rebind.example/", STAGING);
        assertBlocked(policy(List.of(), "rebind.example", "93.184.216.34", "127.0.0.1"),
                "https://rebind.example/", STAGING);
        assertBlocked(policy(List.of(), "rebind.example", "2606:2800:220:1::1", "fd12::1"),
                "https://rebind.example/", STAGING);
    }

    @Test
    void aHostMixingAnAllowedAndAnUnlistedPrivateAddressIsBlocked() {
        assertBlocked(policy(List.of("10.0.0.5:443"), "rebind.example", "10.0.0.5", "10.0.0.6"),
                "https://rebind.example/", STAGING);
    }

    @Test
    void theHostIsResolvedExactlyOnceAndTheValidatedAddressIsReturned() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        List<InetAddress> answers = new ArrayList<>(List.of(
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new StudioProperties(), null, host -> {
            lookups.incrementAndGet();
            return List.of(answers.remove(0));
        });

        OutboundUrlPolicy.Target target = policy.check("https://rebind.example/", STAGING);

        assertEquals(1, lookups.get());
        assertEquals("93.184.216.34", target.address().getHostAddress());
    }

    @Test
    void aNameThatDoesNotResolveIsBlockedUnlessTheDeprecatedFlagIsOnAndThenCarriesNoAddress() {
        StudioProperties properties = new StudioProperties();
        OutboundUrlPolicy.NameResolver unresolved = host -> {
            throw new UnknownHostException(host);
        };
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OutboundUrlPolicy(properties, null, unresolved).check("http://gone.example/", STAGING));
        assertTrue(exception.getMessage().contains("could not be resolved"), exception.getMessage());

        properties.getRun().setAllowPrivateNetworks(true);
        assertNull(new OutboundUrlPolicy(properties, null, unresolved).check("http://gone.example/", STAGING).address());
    }

    // Error messages

    @Test
    void aBlockedRequestNamesTheHostAndEnvironmentAndSaysToAskAnAdmin() {
        OutboundUrlPolicy policy = policy(List.of("10.9.9.9:443"), "orders.internal", "10.0.0.5");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy.check("https://orders.internal/v1", STAGING));
        assertEquals("Run target host orders.internal is not allowed (environment \"Staging\"). Ask an admin to allow it.",
                exception.getMessage());
        assertFalse(exception.getMessage().contains("10.0.0.5"));
        assertFalse(exception.getMessage().contains("10.9.9.9"));
    }

    @Test
    void aBlockedRequestWithNoEnvironmentSaysSo() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy(List.of()).check("http://10.0.0.5/", OutboundUrlPolicy.Scope.NONE));
        assertTrue(exception.getMessage().contains("no environment selected"), exception.getMessage());
    }

    private static OutboundUrlPolicy policy(List<String> allowlist, String name, String... addresses) {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING_ID, allowlist));
        return new OutboundUrlPolicy(properties, null, host -> {
            if (!host.equals(name)) {
                throw new UnknownHostException(host);
            }
            List<InetAddress> resolved = new ArrayList<>();
            for (String address : addresses) {
                resolved.add(InetAddress.getByName(address));
            }
            return resolved;
        });
    }

    private static OutboundUrlPolicy policy(List<String> allowlist) {
        return policy(allowlist, "unused.example");
    }

    private static void assertBlocked(OutboundUrlPolicy policy, String url, OutboundUrlPolicy.Scope scope) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> policy.check(url, scope), url);
        assertTrue(exception.getMessage().contains("not allowed"), exception.getMessage());
    }

    private static void assertAddress(String expected, byte[] actual) {
        try {
            assertEquals(expected, InetAddress.getByAddress(actual).getHostAddress());
        } catch (UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }
}
