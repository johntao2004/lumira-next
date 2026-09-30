package com.lumira.platform.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Classifies outbound destinations so administrator-configured endpoints cannot be turned into a
 * request forwarder for loopback, private, link-local (cloud metadata) or otherwise reserved
 * networks.
 */
public final class PublicHosts {
    private PublicHosts() {}

    /**
     * Syntactic check only: a public-looking DNS name or a public IP literal. Never resolves names.
     */
    public static boolean isAllowed(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")
                || normalized.endsWith(".internal")
                || normalized.endsWith(".lan")
                || normalized.endsWith(".home")) return false;
        String address =
                normalized.startsWith("[") && normalized.endsWith("]")
                        ? normalized.substring(1, normalized.length() - 1)
                        : normalized;
        boolean ipv4Literal = address.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        boolean ipv6Literal = address.contains(":");
        if (!ipv4Literal && !ipv6Literal) {
            return normalized.matches(
                    "(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
        }
        try {
            return isPublicAddress(InetAddress.getByName(address));
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    /**
     * {@link #isAllowed} plus a DNS check that every resolved address is public. Use at connection
     * time.
     */
    public static boolean resolvesPublic(String host) {
        if (!isAllowed(host)) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        String address =
                normalized.startsWith("[") && normalized.endsWith("]")
                        ? normalized.substring(1, normalized.length() - 1)
                        : normalized;
        if (address.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || address.contains(":")) return true;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            return addresses.length > 0
                    && Arrays.stream(addresses).allMatch(PublicHosts::isPublicAddress);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    /**
     * SMTP relays are commonly private (corporate mail servers), so only the addresses that are
     * never a mail server are refused: unspecified, link-local (cloud metadata services) and
     * multicast/broadcast.
     */
    public static boolean isMailTargetAllowed(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        return !(bytes.length == 4 && (bytes[0] & 0xff) >= 224);
    }

    /**
     * Syntactic check for a configured SMTP host: dotted-quad literals must be allowed, numeric
     * shorthand is refused.
     */
    public static boolean isMailHostAllowed(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            try {
                return isMailTargetAllowed(InetAddress.getByName(host));
            } catch (UnknownHostException invalid) {
                return false;
            }
        }
        // Single-label numeric or hex forms ("2130706433", "0x7f000001") are parsed as IPv4 by
        // resolvers.
        return !host.matches("(?i)(?:0x[0-9a-f]+|\\d+)(?:\\.(?:0x[0-9a-f]+|\\d+))*")
                && !host.equalsIgnoreCase("localhost")
                && !host.toLowerCase(Locale.ROOT).endsWith(".localhost");
    }

    public static boolean isPublicAddress(InetAddress parsed) {
        byte[] bytes = parsed.getAddress();
        if (parsed.isAnyLocalAddress()
                || parsed.isLoopbackAddress()
                || parsed.isLinkLocalAddress()
                || parsed.isSiteLocalAddress()
                || parsed.isMulticastAddress()) return false;
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0
                    && first != 10
                    && first != 127
                    && first < 224
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 192 && second == 0 && (third == 0 || third == 2))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (bytes.length == 16) {
            // IPv4-translated IPv6 (::ffff:0:0/96) is an IPv4 transport form, so classify the
            // embedded address rather than treating the 16-byte value as globally routable.
            if (isIpv4Translated(bytes)) {
                try {
                    return isPublicAddress(
                            InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException exception) {
                    return false;
                }
            }
            if ((bytes[0] & 0xfe) == 0xfc
                    || (bytes[0] & 0xff) == 0xff
                    || ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80)) return false;
            // Documentation (2001:db8::/32), 6to4 (2002::/16) and NAT64 (64:ff9b::/96) can all
            // reach IPv4 space.
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x02) return false;
            if ((bytes[0] & 0xff) == 0x20
                    && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8) return false;
            if ((bytes[0] & 0xff) == 0x00
                    && (bytes[1] & 0xff) == 0x64
                    && (bytes[2] & 0xff) == 0xff
                    && (bytes[3] & 0xff) == 0x9b) return false;
            boolean ipv4Mapped = true;
            for (int index = 0; index < 10; index++) ipv4Mapped &= bytes[index] == 0;
            ipv4Mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
            if (ipv4Mapped) {
                try {
                    return isPublicAddress(
                            InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException exception) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** True only for the RFC 2544 benchmarking range used by some local DNS interception tools. */
    public static boolean isSyntheticAddress(InetAddress parsed) {
        byte[] bytes = parsed.getAddress();
        if (bytes.length == 4) return isSyntheticIpv4(bytes);
        if (bytes.length != 16) return false;
        if (isIpv4Translated(bytes)) {
            try {
                return isSyntheticAddress(
                        InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
            } catch (UnknownHostException exception) {
                return false;
            }
        }
        boolean ipv4Mapped = true;
        for (int index = 0; index < 10; index++) ipv4Mapped &= bytes[index] == 0;
        ipv4Mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        if (!ipv4Mapped) return false;
        try {
            return isSyntheticAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean isSyntheticIpv4(byte[] bytes) {
        return (bytes[0] & 0xff) == 198 && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19);
    }

    private static boolean isIpv4Translated(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int index = 0; index < 8; index++) {
            if (bytes[index] != 0) return false;
        }
        return (bytes[8] & 0xff) == 0xff
                && (bytes[9] & 0xff) == 0xff
                && bytes[10] == 0
                && bytes[11] == 0;
    }
}
