package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.TunnelRefusedException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP clients for an environment's connection settings (TLS verification, extra CA, proxy). Redirects are never
 * followed by the client: {@link TestRunService} follows them itself so every hop passes the outbound URL policy.
 * Clients are cached per distinct TLS setting.
 * <p>
 * Each request connects to the address the policy validated, and no DNS lookup happens for the target. The request's
 * host name still sets the Host header, TLS SNI and the certificate check. {@code java.net.http.HttpClient} cannot
 * connect to one address while verifying the certificate against another name, so sending goes through Apache
 * HttpClient; the JDK {@link HttpRequest} is kept because its builder validates header names, values and restricted
 * headers.
 */
final class NetworkClients {

    private static final int MAX_CACHED = 32;
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);
    private static final String PROXY_ATTRIBUTE = "studio.proxy";
    private static final Pattern STATUS_CODE = Pattern.compile("\\b(\\d{3})\\b");

    private final Map<Key, CloseableHttpClient> clients = new ConcurrentHashMap<>();

    private record Key(boolean verifySsl, String caCertificate) {
    }

    /**
     * A client for the environment's TLS settings. {@code proxy} is the policy-checked proxy (null for none), which is
     * also connected to by its validated address.
     */
    Channel channel(StudioEnvironmentNetwork network, OutboundUrlPolicy.Target proxy) {
        Key key = network == null
                ? new Key(true, null)
                : new Key(network.verifiesSsl(), StringUtils.trimToNull(network.caCertificate()));
        if (clients.size() > MAX_CACHED) {
            List<CloseableHttpClient> evicted = new ArrayList<>(clients.values());
            clients.clear();
            evicted.forEach(client -> client.close(CloseMode.GRACEFUL));
        }
        return new Channel(clients.computeIfAbsent(key, NetworkClients::build), proxy);
    }

    private static CloseableHttpClient build(Key key) {
        SSLContext context = sslContext(key);
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(key.verifySsl()
                                ? new DefaultClientTlsStrategy(context)
                                : new DefaultClientTlsStrategy(context, HostnameVerificationPolicy.CLIENT,
                                NoopHostnameVerifier.INSTANCE))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(CONNECT_TIMEOUT)
                                .setValidateAfterInactivity(TimeValue.ofMilliseconds(500))
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(CONNECT_TIMEOUT).build())
                        .setMaxConnTotal(200)
                        .setMaxConnPerRoute(50)
                        .build())
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE) {
                    @Override
                    protected HttpHost determineProxy(HttpHost target, HttpContext context) {
                        return (HttpHost) context.getAttribute(PROXY_ATTRIBUTE);
                    }
                })
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAutomaticRetries()
                .disableAuthCaching()
                .build();
    }

    /** Sends requests over one client and proxy setting. */
    static final class Channel {
        private final CloseableHttpClient client;
        private final HttpHost proxy;

        private Channel(CloseableHttpClient client, OutboundUrlPolicy.Target proxy) {
            this.client = client;
            // The JDK client this replaced always spoke plain HTTP to the proxy, whatever the proxy URL's scheme.
            this.proxy = proxy == null ? null : new HttpHost("http", proxy.address(), bare(proxy.host()), proxy.port());
        }

        HttpResponse<byte[]> send(HttpRequest request, byte[] payload, OutboundUrlPolicy.Target target)
                throws IOException, InterruptedException {
            String scheme = target.uri().getScheme();
            String host = bare(target.host());
            HttpHost endpoint;
            if (proxy != null) {
                // The proxy opens the connection to the target and does its own name lookup.
                endpoint = new HttpHost(scheme, host, target.port());
            } else if (target.address() == null) {
                throw new UnknownHostException(target.host());
            } else {
                endpoint = new HttpHost(scheme, target.address(), host, target.port());
            }
            BasicClassicHttpRequest outbound = new BasicClassicHttpRequest(request.method(), request.uri());
            request.headers().map().forEach((name, values) -> values.forEach(value -> outbound.addHeader(name, value)));
            if (payload != null) {
                outbound.setEntity(new ByteArrayEntity(payload, null));
            }
            Timeout timeout = request.timeout().map(Duration::toMillis).map(Timeout::ofMilliseconds)
                    .orElse(Timeout.ofSeconds(30));
            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(RequestConfig.custom()
                    .setResponseTimeout(timeout)
                    .setConnectionRequestTimeout(timeout)
                    .setRedirectsEnabled(false)
                    .setExpectContinueEnabled(false)
                    .setProtocolUpgradeEnabled(false)
                    .build());
            context.setAttribute(PROXY_ATTRIBUTE, proxy);
            try {
                return client.execute(endpoint, outbound, context, response -> received(request, response));
            } catch (ConnectTimeoutException exception) {
                throw new HttpConnectTimeoutException(exception.getMessage());
            } catch (SocketTimeoutException exception) {
                throw new HttpTimeoutException(exception.getMessage());
            } catch (InterruptedIOException exception) {
                throw new InterruptedException(exception.getMessage());
            } catch (NoHttpResponseException exception) {
                throw new EOFException(exception.getMessage());
            } catch (ClientProtocolException exception) {
                if (exception.getCause() instanceof TunnelRefusedException refused) {
                    Matcher status = STATUS_CODE.matcher(StringUtils.defaultString(refused.getMessage()));
                    throw new IOException("Tunnel failed, got: " + (status.find() ? status.group(1) : "an error"));
                }
                throw exception;
            }
        }

        private static HttpResponse<byte[]> received(HttpRequest request, ClassicHttpResponse response)
                throws IOException {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Header header : response.getHeaders()) {
                headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                        .add(StringUtils.defaultString(header.getValue()));
            }
            byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            return new Received(response.getCode(), request, HttpHeaders.of(headers, (name, value) -> true), body);
        }

        /** {@link URI#getHost()} keeps the brackets of an IPv6 literal; {@code HttpHost} adds its own. */
        private static String bare(String host) {
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        }
    }

    private record Received(int statusCode, HttpRequest request, HttpHeaders headers, byte[] body)
            implements HttpResponse<byte[]> {
        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
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
