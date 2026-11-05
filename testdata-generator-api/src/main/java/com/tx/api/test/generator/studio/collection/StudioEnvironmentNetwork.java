package com.tx.api.test.generator.studio.collection;

import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Connection settings of an environment, used by Try / Run / Run plan and by api-automation (environment file).
 * Every field is optional; {@code null} keeps the default:
 * <ul>
 *   <li>{@code followRedirects}: follow 3xx responses (each hop is checked by the outbound URL policy). Default off.</li>
 *   <li>{@code verifySsl}: check the server certificate and host name. Default on; off accepts self-signed certs.</li>
 *   <li>{@code caCertificate}: extra trusted CA certificates (PEM), on top of the default trust store.</li>
 *   <li>{@code proxyUrl}: {@code http://host:port} HTTP(S) proxy for the API calls (no credentials in the URL).</li>
 *   <li>{@code requestTimeoutMs}: how long to wait for a response, instead of the case's max time + 5 s.</li>
 * </ul>
 */
public record StudioEnvironmentNetwork(
        Boolean followRedirects,
        Boolean verifySsl,
        String caCertificate,
        String proxyUrl,
        Long requestTimeoutMs
) {
    public static final long MIN_TIMEOUT_MS = 1_000L;

    public boolean followsRedirects() {
        return Boolean.TRUE.equals(followRedirects);
    }

    public boolean verifiesSsl() {
        return !Boolean.FALSE.equals(verifySsl);
    }

    public boolean isDefault() {
        return !followsRedirects() && verifiesSsl() && StringUtils.isBlank(caCertificate)
                && StringUtils.isBlank(proxyUrl) && requestTimeoutMs == null;
    }

    /** Trimmed and validated copy; {@code null} when every setting is the default. */
    public static StudioEnvironmentNetwork normalize(StudioEnvironmentNetwork network) {
        if (network == null) {
            return null;
        }
        String ca = StringUtils.trimToNull(network.caCertificate());
        if (ca != null) {
            parseCertificates(ca);
        }
        String proxy = StringUtils.trimToNull(network.proxyUrl());
        if (proxy != null) {
            proxyUri(proxy);
        }
        Long timeout = network.requestTimeoutMs();
        if (timeout != null && timeout <= 0) {
            timeout = null;
        }
        if (timeout != null && timeout < MIN_TIMEOUT_MS) {
            throw new CollectionFormatException("Request timeout must be at least " + MIN_TIMEOUT_MS + " ms");
        }
        StudioEnvironmentNetwork normalized = new StudioEnvironmentNetwork(
                Boolean.TRUE.equals(network.followRedirects()) ? Boolean.TRUE : null,
                Boolean.FALSE.equals(network.verifySsl()) ? Boolean.FALSE : null,
                ca, proxy, timeout);
        return normalized.isDefault() ? null : normalized;
    }

    /** {@code http(s)://host:port} with no user info, path or query. */
    public static URI proxyUri(String proxyUrl) {
        URI uri;
        try {
            uri = URI.create(proxyUrl.trim().contains("://") ? proxyUrl.trim() : "http://" + proxyUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new CollectionFormatException("Proxy URL is not valid: " + proxyUrl);
        }
        String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new CollectionFormatException("Proxy URL must start with http:// or https://");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new CollectionFormatException("Proxy URL needs a host, such as http://proxy.example.com:8080");
        }
        if (uri.getUserInfo() != null) {
            throw new CollectionFormatException("Proxy URL must not contain a user name or password");
        }
        if (StringUtils.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath()) || uri.getQuery() != null) {
            throw new CollectionFormatException("Proxy URL must be only http://host:port");
        }
        return uri;
    }

    public static int proxyPort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** X.509 certificates from PEM text (one or more BEGIN CERTIFICATE blocks). */
    public static List<X509Certificate> parseCertificates(String pem) {
        if (StringUtils.isBlank(pem) || !pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw new CollectionFormatException("CA certificate must be PEM text starting with -----BEGIN CERTIFICATE-----");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certificates = new ArrayList<>();
            for (var certificate : factory.generateCertificates(
                    new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)))) {
                certificates.add((X509Certificate) certificate);
            }
            if (certificates.isEmpty()) {
                throw new CollectionFormatException("CA certificate text has no certificate");
            }
            return certificates;
        } catch (CertificateException exception) {
            throw new CollectionFormatException("CA certificate is not a valid PEM certificate: "
                    + StringUtils.defaultString(exception.getMessage()));
        }
    }
}
