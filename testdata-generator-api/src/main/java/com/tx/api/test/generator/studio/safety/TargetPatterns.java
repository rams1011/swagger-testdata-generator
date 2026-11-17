package com.tx.api.test.generator.studio.safety;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads and matches protected targets. Hosts are compared in lower case, as ASCII (punycode), without a trailing
 * dot. {@code *.prod.acme.com} matches any host below {@code prod.acme.com}, not {@code prod.acme.com} itself.
 * An IP address or CIDR range is a {@link TargetKind#CIDR} target; a single address is stored as /32 or /128.
 * Nothing here resolves names: the send-time check matches the address {@code OutboundUrlPolicy} already pinned.
 */
public final class TargetPatterns {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");
    private static final int MAX_PORTS = 20;

    private TargetPatterns() {
    }

    /** A target as typed by an admin, checked and in its stored form. */
    public record Parsed(TargetKind kind, String pattern) {
    }

    public static Parsed parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Enter a host such as api.acme.com, a pattern such as *.prod.acme.com, "
                    + "or an IP range such as 10.20.0.0/16");
        }
        if (value.contains("://") || value.contains("/") && !looksLikeAddress(value.substring(0, value.indexOf('/')))) {
            throw new IllegalArgumentException("Enter just the host, such as api.acme.com, without https:// or a path");
        }
        if (looksLikeAddress(value) || value.contains("/")) {
            return new Parsed(TargetKind.CIDR, normalizeCidr(value));
        }
        return new Parsed(TargetKind.HOST, normalizeHostPattern(value));
    }

    static String normalizeHostPattern(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.contains(":")) {
            throw new IllegalArgumentException("Put ports in the Ports field, not in the host");
        }
        boolean wildcard = text.startsWith("*.");
        String rest = wildcard ? text.substring(2) : text;
        if ("*".equals(text) || rest.contains("*")) {
            throw new IllegalArgumentException("A * may only stand for the whole leftmost part, as in *.prod.acme.com");
        }
        String host = normalizeHost(rest);
        if (host == null) {
            throw new IllegalArgumentException("'" + value.trim() + "' isn't a valid host name");
        }
        if (wildcard && !host.contains(".")) {
            throw new IllegalArgumentException("A pattern needs at least two parts after *., such as *.acme.com");
        }
        return wildcard ? "*." + host : host;
    }

    /** Lower case, punycode, no trailing dot; null when it isn't a valid host name. */
    public static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String text = host.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty() || looksLikeAddress(text)) {
            return text.isEmpty() ? null : text;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(text, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (ascii.length() > 253) {
            return null;
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return null;
            }
        }
        return ascii;
    }

    static String normalizeCidr(String value) {
        String text = value.trim();
        int slash = text.indexOf('/');
        String addressText = slash < 0 ? text : text.substring(0, slash);
        InetAddress address = literal(addressText)
                .orElseThrow(() -> new IllegalArgumentException("'" + addressText + "' isn't an IP address"));
        int bits = address.getAddress().length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException invalid) {
                prefix = -1;
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("The range size after / must be 0 to " + bits);
            }
        }
        if (prefix == 0) {
            throw new IllegalArgumentException("A /0 range would protect every address; enter a narrower range");
        }
        byte[] network = mask(address.getAddress(), prefix);
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException("'" + value + "' isn't an IP range");
        }
    }

    /** Ports 1–65535, at most 20, sorted; empty means any port. */
    public static List<Integer> normalizePorts(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Ports must be 1 to 65535");
            }
            sorted.add(port);
        }
        if (sorted.size() > MAX_PORTS) {
            throw new IllegalArgumentException("A target can list at most " + MAX_PORTS + " ports");
        }
        return List.copyOf(sorted);
    }

    static String portsText(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return null;
        }
        return String.join(",", ports.stream().map(String::valueOf).toList());
    }

    static List<Integer> portsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Integer> ports = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                ports.add(Integer.parseInt(part.trim()));
            }
        }
        return ports;
    }

    /** Whether a host target covers {@code host} (already normalized). */
    public static boolean hostMatches(String pattern, String host) {
        if (pattern == null || host == null) {
            return false;
        }
        if (pattern.startsWith("*.")) {
            return host.endsWith(pattern.substring(1));
        }
        return pattern.equals(host);
    }

    /** Whether a CIDR target covers {@code address}. */
    public static boolean cidrContains(String cidr, InetAddress address) {
        if (cidr == null || address == null) {
            return false;
        }
        int slash = cidr.indexOf('/');
        Optional<InetAddress> network = literal(slash < 0 ? cidr : cidr.substring(0, slash));
        if (network.isEmpty()) {
            return false;
        }
        byte[] wanted = network.get().getAddress();
        byte[] actual = address.getAddress();
        if (wanted.length != actual.length) {
            return false;
        }
        int prefix = slash < 0 ? wanted.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
        return java.util.Arrays.equals(mask(actual, prefix), mask(wanted, prefix));
    }

    /**
     * The first target covering a URL's host and port, by name or, for an IP literal, by range. Used for an
     * environment's base URL; a base URL with {{variables}} in its host can't be matched and gives empty.
     */
    public static Optional<ProtectedTarget> matchUrl(List<ProtectedTarget> targets, String url) {
        if (targets == null || targets.isEmpty() || url == null || url.isBlank() || url.contains("{{")) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        String host = normalizeHost(uri.getHost());
        if (host == null) {
            return Optional.empty();
        }
        int port = uri.getPort() > 0 ? uri.getPort() : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
        InetAddress address = literal(host).orElse(null);
        return match(targets, host, address, port);
    }

    /** The first target covering this host name or address on this port. */
    public static Optional<ProtectedTarget> match(List<ProtectedTarget> targets, String host, InetAddress address,
                                                  int port) {
        String normalized = normalizeHost(host);
        for (ProtectedTarget target : targets) {
            if (!target.coversPort(port)) {
                continue;
            }
            boolean hit = target.kind() == TargetKind.HOST
                    ? hostMatches(target.pattern(), normalized)
                    : cidrContains(target.pattern(), address);
            if (hit) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    private static boolean looksLikeAddress(String text) {
        String value = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        return IPV4.matcher(value).matches() || value.contains(":") && IPV6.matcher(value).matches();
    }

    /** An IP literal as an address, never a DNS lookup. */
    static Optional<InetAddress> literal(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!looksLikeAddress(value)) {
            return Optional.empty();
        }
        if (IPV4.matcher(value).matches()) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return Optional.empty();
                }
            }
        }
        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException invalid) {
            return Optional.empty();
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] masked = address.clone();
        for (int index = 0; index < masked.length; index++) {
            int keep = Math.max(0, Math.min(8, prefix - index * 8));
            masked[index] = (byte) (masked[index] & (0xFF << (8 - keep)));
        }
        return masked;
    }
}
