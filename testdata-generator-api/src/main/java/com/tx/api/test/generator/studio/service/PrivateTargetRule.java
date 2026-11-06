package com.tx.api.test.generator.studio.service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * One allowlist entry: an exact host or address, or a CIDR range, always with a port.
 * Wildcards and ranges wider than /8 (IPv4) or /7 (IPv6) are refused.
 */
record PrivateTargetRule(String text, String hostName, byte[] network, int prefix, int port) {

    private static final int MIN_IPV4_PREFIX = 8;
    private static final int MIN_IPV6_PREFIX = 7;

    static PrivateTargetRule parse(String entry) {
        String value = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw invalid(entry, "expected host:port or cidr:port");
        }
        int port = port(value.substring(colon + 1), entry);
        String target = value.substring(0, colon);
        boolean bracketed = target.startsWith("[") && target.endsWith("]");
        if (bracketed) {
            target = target.substring(1, target.length() - 1);
        } else if (target.indexOf(':') >= 0) {
            throw invalid(entry, "IPv6 entries need brackets, such as [fd12::1]:443");
        }
        int slash = target.indexOf('/');
        if (slash >= 0) {
            return cidr(value, target.substring(0, slash), target.substring(slash + 1), port, entry);
        }
        if (bracketed || OutboundUrlPolicy.endsInNumericLabel(target)) {
            byte[] address = literal(target, entry);
            return new PrivateTargetRule(value, null, address, address.length * 8, port);
        }
        return new PrivateTargetRule(value, hostName(target, entry), null, 0, port);
    }

    boolean matches(String host, InetAddress address, int requestPort) {
        if (requestPort != port) {
            return false;
        }
        if (hostName != null) {
            return hostName.equals(host);
        }
        byte[] raw = address.getAddress();
        if (raw.length != network.length) {
            return false;
        }
        int whole = prefix / 8;
        for (int i = 0; i < whole; i++) {
            if (raw[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (raw[whole] & mask) == (network[whole] & mask);
    }

    private static PrivateTargetRule cidr(String text, String address, String prefixText, int port, String entry) {
        byte[] network = literal(address, entry);
        int prefix;
        try {
            prefix = Integer.parseInt(prefixText);
        } catch (NumberFormatException exception) {
            throw invalid(entry, "the CIDR prefix is not a number");
        }
        int minimum = network.length == 4 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
        if (prefix < minimum || prefix > network.length * 8) {
            throw invalid(entry, "the CIDR prefix must be between " + minimum + " and " + network.length * 8);
        }
        return new PrivateTargetRule(text, null, network, prefix, port);
    }

    /** Parses address text only. A value that is not an address never reaches DNS. */
    private static byte[] literal(String address, String entry) {
        try {
            if (address.indexOf(':') >= 0) {
                return InetAddress.getByName("[" + address + "]").getAddress();
            }
        } catch (UnknownHostException exception) {
            throw invalid(entry, "the address is not valid");
        }
        byte[] ipv4 = OutboundUrlPolicy.parseIpv4(address);
        if (ipv4 == null) {
            throw invalid(entry, "the address is not valid");
        }
        return ipv4;
    }

    private static String hostName(String host, String entry) {
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        try {
            name = IDN.toASCII(name);
        } catch (IllegalArgumentException exception) {
            throw invalid(entry, "the host name is not valid");
        }
        if (!name.matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")) {
            throw invalid(entry, "the host name is not valid; wildcards are not supported");
        }
        return name;
    }

    private static int port(String text, String entry) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException exception) {
            throw invalid(entry, "the port is not a number");
        }
        throw invalid(entry, "the port must be between 1 and 65535");
    }

    private static IllegalArgumentException invalid(String entry, String reason) {
        return new IllegalArgumentException("Invalid private target allowlist entry '" + entry + "': " + reason + ".");
    }
}
