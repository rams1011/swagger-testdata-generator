package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP clients for an environment's connection settings (TLS verification, extra CA, proxy). Redirects are never
 * followed by the client: {@link TestRunService} follows them itself so every hop passes the outbound URL policy.
 * Clients are cached per distinct setting.
 */
final class NetworkClients {

    private static final int MAX_CACHED = 32;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final Map<Key, HttpClient> clients = new ConcurrentHashMap<>();
    private final HttpClient defaultClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Key(boolean verifySsl, String caCertificate, String proxyUrl) {
    }

    HttpClient client(StudioEnvironmentNetwork network) {
        if (network == null || (network.verifiesSsl() && StringUtils.isBlank(network.caCertificate())
                && StringUtils.isBlank(network.proxyUrl()))) {
            return defaultClient;
        }
        Key key = new Key(network.verifiesSsl(), StringUtils.trimToNull(network.caCertificate()),
                StringUtils.trimToNull(network.proxyUrl()));
        if (clients.size() > MAX_CACHED) {
            clients.clear();
        }
        return clients.computeIfAbsent(key, NetworkClients::build);
    }

    private static HttpClient build(Key key) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(sslContext(key));
        if (key.proxyUrl() != null) {
            URI proxy = StudioEnvironmentNetwork.proxyUri(key.proxyUrl());
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.getHost(),
                    StudioEnvironmentNetwork.proxyPort(proxy))));
        }
        return builder.build();
    }

    private static SSLContext sslContext(Key key) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            TrustManager trust = !key.verifySsl()
                    ? new TrustAll()
                    : key.caCertificate() == null
                    ? defaultTrust()
                    : new WithExtraCa(defaultTrust(), extraTrust(key.caCertificate()));
            context.init(null, new TrustManager[] {trust}, null);
            return context;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not set up TLS for this environment: " + exception.getMessage());
        }
    }

    private static X509ExtendedTrustManager defaultTrust() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return firstX509(factory);
    }

    private static X509ExtendedTrustManager extraTrust(String pem) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        List<X509Certificate> certificates = StudioEnvironmentNetwork.parseCertificates(pem);
        for (int i = 0; i < certificates.size(); i++) {
            store.setCertificateEntry("studio-ca-" + i, certificates.get(i));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return firstX509(factory);
    }

    private static X509ExtendedTrustManager firstX509(TrustManagerFactory factory) {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509 trust manager");
    }

    /** Default trust store first, then the environment's CA; both keep host name verification. */
    private static final class WithExtraCa extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager first;
        private final X509ExtendedTrustManager second;

        WithExtraCa(X509ExtendedTrustManager first, X509ExtendedTrustManager second) {
            this.first = Objects.requireNonNull(first);
            this.second = Objects.requireNonNull(second);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException notInDefault) {
                second.checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException notInDefault) {
                second.checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException notInDefault) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            first.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            first.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            first.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = first.getAcceptedIssuers();
            X509Certificate[] b = second.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }

    /**
     * Verify SSL off: accepts any certificate and skips the host name check (the extended methods are where the
     * JDK performs endpoint identification, so leaving them empty turns it off for this client only).
     */
    private static final class TrustAll extends X509ExtendedTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
