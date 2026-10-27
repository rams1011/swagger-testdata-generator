package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundUrlPolicyTest {

    @Test
    void blocksCloudMetadataLoopbackAndPrivateAddresses() {
        assertNotAllowed("http://169.254.169.254/latest/meta-data");
        assertNotAllowed("http://169.254.170.2/");
        assertNotAllowed("http://100.100.100.200/");
        assertNotAllowed("http://127.0.0.1/");
        assertNotAllowed("http://[::1]/");
        assertNotAllowed("http://10.1.2.3/admin");
        assertNotAllowed("http://192.168.1.10/");
        assertNotAllowed("http://2130706433/");
        assertNotAllowed("http://0177.0.0.1/");
        assertNotAllowed("http://metadata.google.internal/");
    }

    @Test
    void blocksANameThatResolvesToAPrivateAddress() throws Exception {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlPolicy.check("https://rebind.example/v1", false, List.of(),
                        host -> List.of(InetAddress.getByName("10.1.2.3"))));
        assertTrue(exception.getMessage().contains("not allowed"));
        assertFalse(exception.getMessage().contains("10.1.2.3"));
    }

    @Test
    void allowsAPublicAddressAndAnAllowListedHost() throws Exception {
        InetAddress example = InetAddress.getByName("93.184.216.34");
        OutboundUrlPolicy.check("https://api.example.com/v1", false, List.of(), host -> List.of(example));
        OutboundUrlPolicy.check("https://api.example.com/v1", false, List.of("*.example.com"), host -> List.of(example));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlPolicy.check("https://api.example.com/v1", false, List.of("*.partner.test"),
                        host -> List.of(example)));
    }

    @Test
    void rejectsSchemesAndEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlPolicy.check("file:///tmp/secret", false, List.of(), host -> List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlPolicy.check("http://user:secret@api.example.com/", false, List.of(), host -> List.of()));
    }

    @Test
    void privateNetworksStayOpenForLocalDesktopExceptMetadata() throws Exception {
        OutboundUrlPolicy.check("http://127.0.0.1:8080/health", true, List.of(), host -> List.of());
        assertNotAllowed("http://169.254.169.254/latest/meta-data", true);
        assertNotAllowed("http://metadata.google.internal/computeMetadata/v1/", true);
        AtomicInteger lookups = new AtomicInteger();
        OutboundUrlPolicy.check("http://localhost/health", true, List.of(), host -> {
            lookups.incrementAndGet();
            throw new UnknownHostException(host);
        });
        assertTrue(lookups.get() >= 1);
    }

    @Test
    void tryDoesNotCallAPrivateAddress() {
        StudioProperties properties = new StudioProperties();
        TestRunService service = new TestRunService(null, properties, new OutboundUrlPolicy(properties));
        StudioTestCase testCase = new StudioTestCase();
        testCase.setMethod("GET");
        testCase.setBaseUrl("http://10.1.2.3");
        testCase.setEndpoint("/admin");
        testCase.setStatusCode(200);

        RunTestResult result = service.execute(testCase, null);

        assertFalse(result.passed());
        assertTrue(result.error().contains("not allowed"));
    }

    @Test
    void oauthTokenUrlIsCheckedBeforeTheCall() {
        StudioProperties properties = new StudioProperties();
        TestRunService service = new TestRunService(null, properties, new OutboundUrlPolicy(properties));
        StudioTestCase testCase = new StudioTestCase();
        testCase.setMethod("GET");
        testCase.setBaseUrl("https://api.example.com");
        testCase.setEndpoint("/items");
        RunAuth auth = new RunAuth(
                "OAUTH2", null, null, null, null, null, null,
                "client", "secret", "http://169.254.169.254/token", "client_credentials");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.execute(testCase, auth));
        assertTrue(exception.getMessage().contains("not allowed"));
    }

    private static void assertNotAllowed(String url) {
        assertNotAllowed(url, false);
    }

    private static void assertNotAllowed(String url, boolean allowPrivateNetworks) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlPolicy.check(url, allowPrivateNetworks, List.of(), host -> {
                    throw new UnknownHostException(host);
                }));
        assertTrue(exception.getMessage().contains("not allowed"), exception.getMessage());
    }
}
