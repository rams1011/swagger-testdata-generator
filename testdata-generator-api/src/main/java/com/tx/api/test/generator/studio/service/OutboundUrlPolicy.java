package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Stops Try and Run plan from sending the API process to loopback, private, link-local,
 * or cloud-metadata addresses. Redirects are already disabled on the HTTP client.
 * An optional host list can narrow the public hosts that remain.
 */
@Component
public class OutboundUrlPolicy {

    private final StudioProperties properties;

    public OutboundUrlPolicy(StudioProperties properties) {
        this.properties = properties;
    }

    public void check(String url) {
        StudioProperties.Run run = properties.getRun();
        check(url, run.isAllowPrivateNetworks(), run.allowHostRules(), OutboundUrlPolicy::resolve);
    }

    static void check(String url, boolean allowPrivateNetworks, List<String> allowHosts, NameResolver resolver) {
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
            throw new IllegalArgumentException("Run target host is missing.");
        }
        String normalized = normalizeHost(host);
        if (normalized.indexOf('%') >= 0) {
            reject(normalized);
        }
        if (isMetadataName(normalized)) {
            reject(normalized);
        }
        if (!hostAllowed(normalized, allowHosts)) {
            throw new IllegalArgumentException("Run target host " + display(normalized) + " is not on the allow list.");
        }
        if (isObfuscatedAddress(normalized)) {
            reject(normalized);
        }
        InetAddress literal = literalAddress(normalized);
        if (literal != null) {
            assertAddressAllowed(normalized, literal, allowPrivateNetworks);
            return;
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException exception) {
            if (allowPrivateNetworks) {
                return;
            }
            throw new IllegalArgumentException("Run target host " + display(normalized) + " could not be resolved.");
        }
        if (addresses == null || addresses.isEmpty()) {
            if (allowPrivateNetworks) {
                return;
            }
            throw new IllegalArgumentException("Run target host " + display(normalized) + " could not be resolved.");
        }
        for (InetAddress address : addresses) {
            assertAddressAllowed(normalized, address, allowPrivateNetworks);
        }
    }

    private static void assertAddressAllowed(String host, InetAddress address, boolean allowPrivateNetworks) {
        if (isMetadataAddress(address) || (!allowPrivateNetworks && isBlockedAddress(address))) {
            reject(host);
        }
    }

    private static void reject(String host) {
        throw new IllegalArgumentException("Run target host " + display(host) + " is not allowed.");
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

    private static String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return IDN.toASCII(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Run target host " + display(value) + " is not allowed.");
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

    private static boolean isObfuscatedAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return false;
        }
        if (host.startsWith("0x") || host.contains(".0x")) {
            return true;
        }
        if (host.chars().allMatch(Character::isDigit)) {
            return true;
        }
        return host.indexOf('.') >= 0 && host.chars().allMatch(ch -> ch == '.' || Character.isDigit(ch))
                && !isCanonicalIpv4(host);
    }

    private static boolean isCanonicalIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return false;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress literalAddress(String host) {
        if (host.indexOf(':') >= 0 || isCanonicalIpv4(host)) {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Run target host " + display(host) + " is not allowed.");
            }
        }
        return null;
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

    @FunctionalInterface
    interface NameResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
