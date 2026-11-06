package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stops Try and Run plan from sending the API process to loopback, private, link-local,
 * or cloud-metadata addresses. A host is resolved once and every address is validated; callers connect to the
 * returned address, so a later DNS answer cannot change the destination.
 * <p>
 * Always blocked: cloud metadata, link-local, this server's own HTTP and management ports, and the configured
 * infrastructure ports on non-public addresses. Other private addresses need an allowlist entry for the environment
 * (or the deprecated {@code allowPrivateNetworks} fallback). An optional host list can narrow the hosts that remain.
 */
@Component
public class OutboundUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutboundUrlPolicy.class);
    static final String FALLBACK_RULE = "allow-private-networks (deprecated)";

    private final StudioProperties properties;
    private final Environment environment;
    private final NameResolver resolver;

    public OutboundUrlPolicy(StudioProperties properties) {
        this(properties, null, OutboundUrlPolicy::resolve);
    }

    @Autowired
    public OutboundUrlPolicy(StudioProperties properties, Environment environment) {
        this(properties, environment, OutboundUrlPolicy::resolve);
    }

    OutboundUrlPolicy(StudioProperties properties, Environment environment, NameResolver resolver) {
        this.properties = properties;
        this.environment = environment;
        this.resolver = resolver;
        StudioProperties.Run run = properties.getRun();
        run.blockedInfrastructurePortSet();
        for (Map.Entry<String, List<String>> entry : run.getPrivateTargetAllowlist().entrySet()) {
            if (entry.getKey().length() > 36) {
                throw new IllegalArgumentException("Private target allowlist key '" + entry.getKey()
                        + "' is not an environment id.");
            }
            entry.getValue().forEach(PrivateTargetRule::parse);
        }
        if (run.isAllowPrivateNetworks() && properties.isAuthEnabled()) {
            log.warn("testdata.run.allow-private-networks is deprecated and opens private networks to every "
                    + "environment; use testdata.run.private-target-allowlist");
        }
    }

    public Target check(String url) {
        return check(url, Scope.NONE);
    }

    /** Throws {@link IllegalArgumentException} with a message safe to show the user when the target is blocked. */
    public Target check(String url, Scope scope) {
        StudioProperties.Run run = properties.getRun();
        List<PrivateTargetRule> allowlist = run.getPrivateTargetAllowlist()
                .getOrDefault(scope.environmentId() == null ? "" : scope.environmentId(), List.of())
                .stream().map(PrivateTargetRule::parse).toList();
        Rules rules = new Rules(run.isAllowPrivateNetworks(), run.allowHostRules(), allowlist,
                run.blockedInfrastructurePortSet(), serverPorts());
        return evaluate(url, rules, scope, resolver);
    }

    static Target check(String url, boolean allowPrivateNetworks, List<String> allowHosts, NameResolver resolver) {
        return evaluate(url, new Rules(allowPrivateNetworks, allowHosts, List.of(), Set.of(), Set.of()),
                Scope.NONE, resolver);
    }

    static Target evaluate(String url, Rules rules, Scope scope, NameResolver resolver) {
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Run target must use http or https.");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Run target must not include credentials in the URL.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            rejectNumericAuthority(uri, rules, scope);
            throw new IllegalArgumentException("Run target host is missing.");
        }
        String normalized = normalizeHost(host, scope);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        if (normalized.indexOf('%') >= 0 || isMetadataName(normalized)) {
            throw alwaysBlocked(normalized, scope);
        }
        if (!hostAllowed(normalized, rules.allowHosts())) {
            throw new IllegalArgumentException("Run target host " + display(normalized) + " is not on the allow list ("
                    + scope.label() + "). Ask an admin to allow it.");
        }
        List<InetAddress> addresses = addresses(normalized, scope, resolver);
        if (addresses.isEmpty()) {
            if (rules.allowPrivateNetworks()) {
                return new Target(uri, normalized, port, null, null);
            }
            throw new IllegalArgumentException("Run target host " + display(normalized) + " could not be resolved ("
                    + scope.label() + ").");
        }
        String privateRule = null;
        for (InetAddress address : addresses) {
            InetAddress effective = embeddedIpv4(address);
            if (isAlwaysBlocked(address, port, rules) || isAlwaysBlocked(effective, port, rules)) {
                throw alwaysBlocked(normalized, scope);
            }
            if (isBlockedAddress(address) || isBlockedAddress(effective)) {
                String rule = allowedBy(normalized, effective, port, rules);
                if (rule == null) {
                    throw new IllegalArgumentException("Run target host " + display(normalized)
                            + " is not allowed (" + scope.label() + "). Ask an admin to allow it.");
                }
                privateRule = rule;
            }
        }
        return new Target(uri, normalized, port, addresses.get(0), privateRule);
    }

    /**
     * {@link URI} reports no host for dotted forms such as {@code 127.1} or {@code 0177.0.0.1}. HTTP clients cannot
     * send to such a URI, so it is always refused; the decoded address only decides which message explains why.
     */
    private static void rejectNumericAuthority(URI uri, Rules rules, Scope scope) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return;
        }
        String value = authority.toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        String host = colon >= 0 ? value.substring(0, colon) : value;
        String portText = colon >= 0 ? value.substring(colon + 1) : "";
        if (host.isEmpty() || !endsInNumericLabel(host)) {
            return;
        }
        int port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        if (portText.matches("[0-9]{1,5}")) {
            port = Integer.parseInt(portText);
        }
        byte[] ipv4 = parseIpv4(host);
        if (ipv4 != null) {
            try {
                if (isAlwaysBlocked(InetAddress.getByAddress(ipv4), port, rules)) {
                    throw alwaysBlocked(host, scope);
                }
            } catch (UnknownHostException exception) {
                throw alwaysBlocked(host, scope);
            }
        }
        throw new IllegalArgumentException("Run target host " + display(host) + " is not allowed (" + scope.label()
                + "). Ask an admin to allow it.");
    }

    /** The address list for a host: a parsed literal, or one DNS lookup. Empty when the name does not resolve. */
    private static List<InetAddress> addresses(String host, Scope scope, NameResolver resolver) {
        try {
            if (host.indexOf(':') >= 0) {
                return List.of(InetAddress.getByName(host));
            }
            if (endsInNumericLabel(host)) {
                byte[] ipv4 = parseIpv4(host);
                if (ipv4 == null) {
                    throw new IllegalArgumentException("Run target host " + display(host) + " is not allowed ("
                            + scope.label() + "). Ask an admin to allow it.");
                }
                return List.of(InetAddress.getByAddress(ipv4));
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Run target host " + display(host) + " is not allowed ("
                    + scope.label() + "). Ask an admin to allow it.");
        }
        try {
            List<InetAddress> resolved = resolver.resolve(host);
            return resolved == null ? List.of() : resolved;
        } catch (UnknownHostException exception) {
            return List.of();
        }
    }

    private static String allowedBy(String host, InetAddress address, int port, Rules rules) {
        for (PrivateTargetRule rule : rules.allowlist()) {
            if (rule.matches(host, address, port)) {
                return rule.text();
            }
        }
        return rules.allowPrivateNetworks() ? FALLBACK_RULE : null;
    }

    private static IllegalArgumentException alwaysBlocked(String host, Scope scope) {
        return new IllegalArgumentException("Run target host " + display(host) + " is not allowed (" + scope.label()
                + ") and cannot be added to the allowlist. Ask an admin if this target is needed.");
    }

    private static boolean isAlwaysBlocked(InetAddress address, int port, Rules rules) {
        if (isMetadataAddress(address) || address.isLinkLocalAddress()) {
            return true;
        }
        if (rules.infrastructurePorts().contains(port) && isBlockedAddress(address)) {
            return true;
        }
        return rules.serverPorts().contains(port) && isSelfAddress(address);
    }

    private static boolean isSelfAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException exception) {
            return true;
        }
    }

    private Set<Integer> serverPorts() {
        if (environment == null) {
            return Set.of();
        }
        Set<Integer> ports = new HashSet<>();
        addPort(ports, environment.getProperty("server.port", "8080"));
        addPort(ports, environment.getProperty("local.server.port"));
        addPort(ports, environment.getProperty("management.server.port"));
        addPort(ports, environment.getProperty("local.management.port"));
        return ports;
    }

    private static void addPort(Set<Integer> ports, String value) {
        if (value != null && value.matches("[0-9]{1,5}")) {
            ports.add(Integer.valueOf(value));
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Run target URL is required.");
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Run target URL is invalid.");
        }
    }

    private static String normalizeHost(String host, Scope scope) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return IDN.toASCII(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Run target host " + display(value) + " is not allowed ("
                    + scope.label() + "). Ask an admin to allow it.");
        }
    }

    private static boolean isMetadataName(String host) {
        return "metadata.google.internal".equals(host)
                || "metadata.google.com".equals(host)
                || "metadata".equals(host);
    }

    static boolean hostAllowed(String host, List<String> allowHosts) {
        if (allowHosts == null || allowHosts.isEmpty()) {
            return true;
        }
        for (String rule : allowHosts) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String item = rule.trim().toLowerCase(Locale.ROOT);
            if (item.endsWith(".")) {
                item = item.substring(0, item.length() - 1);
            }
            if (item.startsWith("*.")) {
                String suffix = item.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** A host whose last label is a number is an IPv4 address to every resolver, never a DNS name. */
    static boolean endsInNumericLabel(String host) {
        String last = host.substring(host.lastIndexOf('.') + 1);
        return last.matches("[0-9]+|0[x][0-9a-f]*");
    }

    /**
     * Reads the forms {@code inet_aton} accepts: one to four parts, each decimal, octal ({@code 0177}) or hex
     * ({@code 0x7f}), with the last part filling the remaining bytes. Returns null when the text is not an address.
     */
    static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long value = parseIpv4Part(parts[i]);
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        long result = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (values[i] > 255) {
                return null;
            }
            result |= values[i] << (24 - 8 * i);
        }
        long last = values[parts.length - 1];
        if (last >= 1L << (8 * (5 - parts.length))) {
            return null;
        }
        result |= last;
        return new byte[] {(byte) (result >> 24), (byte) (result >> 16), (byte) (result >> 8), (byte) result};
    }

    private static Long parseIpv4Part(String part) {
        if (!part.matches("[0-9]+|0x[0-9a-f]*")) {
            return null;
        }
        try {
            if (part.startsWith("0x")) {
                return part.length() == 2 ? Long.valueOf(0) : Long.valueOf(part.substring(2), 16);
            }
            if (part.length() > 1 && part.startsWith("0")) {
                return Long.valueOf(part.substring(1), 8);
            }
            return Long.valueOf(part);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * The IPv4 address carried by an IPv4-mapped, IPv4-compatible, NAT64 ({@code 64:ff9b::/96}) or 6to4
     * ({@code 2002::/16}) IPv6 address; otherwise the address itself.
     */
    static InetAddress embeddedIpv4(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length != 16) {
            return address;
        }
        int from = -1;
        if (isIpv4Mapped(raw) || isIpv4Compatible(raw) || isNat64(raw)) {
            from = 12;
        } else if ((raw[0] & 0xff) == 0x20 && (raw[1] & 0xff) == 0x02) {
            from = 2;
        }
        if (from < 0) {
            return address;
        }
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(raw, from, from + 4));
        } catch (UnknownHostException exception) {
            return address;
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (isIpv4Mapped(raw)) {
            try {
                return isBlockedAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(raw, 12, 16)));
            } catch (UnknownHostException exception) {
                return true;
            }
        }
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            if (first == 0 || first == 255) {
                return true;
            }
            // 100.64.0.0/10 shared address space, including some cloud metadata addresses.
            if (first == 100 && (second & 0b11000000) == 64) {
                return true;
            }
            return false;
        }
        if (raw.length == 16) {
            int first = raw[0] & 0xff;
            // fc00::/7 unique local
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    static boolean isMetadataAddress(InetAddress address) {
        byte[] raw = address.getAddress();
        if (isIpv4Mapped(raw)) {
            try {
                return isMetadataAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(raw, 12, 16)));
            } catch (UnknownHostException exception) {
                return true;
            }
        }
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            int third = raw[2] & 0xff;
            int fourth = raw[3] & 0xff;
            if (first == 169 && second == 254 && third == 169 && fourth == 254) {
                return true;
            }
            if (first == 169 && second == 254 && third == 170 && fourth == 2) {
                return true;
            }
            return first == 100 && second == 100 && third == 100 && fourth == 200;
        }
        if (raw.length == 16) {
            // fd00:ec2::254
            return (raw[0] & 0xff) == 0xfd && (raw[1] & 0xff) == 0x00
                    && (raw[2] & 0xff) == 0x0e && (raw[3] & 0xff) == 0xc2
                    && (raw[14] & 0xff) == 0x02 && (raw[15] & 0xff) == 0x54;
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff;
    }

    /** {@code ::} and {@code ::1} share this prefix but are the unspecified and loopback addresses. */
    private static boolean isIpv4Compatible(byte[] raw) {
        for (int i = 0; i < 12; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[12] != 0 || raw[13] != 0 || raw[14] != 0 || (raw[15] & 0xff) > 1;
    }

    private static boolean isNat64(byte[] raw) {
        if (raw[0] != 0 || (raw[1] & 0xff) != 0x64 || (raw[2] & 0xff) != 0xff || (raw[3] & 0xff) != 0x9b) {
            return false;
        }
        for (int i = 4; i < 12; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }

    private static String display(String host) {
        String cleaned = host.replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.length() > 253) {
            return cleaned.substring(0, 253);
        }
        return cleaned;
    }

    /** The environment a request runs in. Build it only from an environment the caller's tenant owns. */
    public record Scope(String environmentId, String environmentName) {
        public static final Scope NONE = new Scope(null, null);
        /** Spec import has no environment, so no environment's allowlist applies to it. */
        public static final Scope SPEC_IMPORT = new Scope(null, "spec import");

        String label() {
            if (environmentId == null) {
                return environmentName == null ? "no environment selected" : environmentName;
            }
            String name = environmentName == null ? environmentId : display(environmentName);
            return "environment \"" + name + "\"";
        }
    }

    /**
     * A validated target. {@code address} is what to connect to; it is null only when the deprecated fallback let an
     * unresolved name through, which a proxy can still reach and a direct connection must refuse.
     * {@code privateRule} names the rule that allowed a private address, and is null for a public target.
     */
    public record Target(URI uri, String host, int port, InetAddress address, String privateRule) {
    }

    record Rules(boolean allowPrivateNetworks, List<String> allowHosts, List<PrivateTargetRule> allowlist,
                 Set<Integer> infrastructurePorts, Set<Integer> serverPorts) {
    }

    @FunctionalInterface
    interface NameResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
