package ar.cetobhb.common.config;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.catalina.util.NetMaskSet;

/**
 * Safety check of the value given to Tomcat's {@code RemoteIpValve} {@code internal-proxies} (verification U-6). A peer
 * inside that set may set the client address through {@code X-Forwarded-For}, so a value that reaches a public address
 * lets any caller forge its rate-limit key.
 *
 * <p>The value is a CIDR-ONLY allowlist: a comma-separated list where EVERY entry lies fully inside one of the private
 * blocks in {@link #ALLOWED}. Containment is checked by prefix arithmetic (the entry prefix is at least as long as
 * the block prefix and the leading bits are equal), so nothing is sampled and no regex is accepted. Tomcat switches to
 * CIDR parsing when the value has a {@code /} after the first character, otherwise it compiles the whole value as ONE
 * regex; this class never accepts the regex form, so a value it accepts is always read by Tomcat as CIDR.
 *
 * <p>Entries are split on {@code ,} and trimmed, exactly as Tomcat does ({@code StringUtils.splitCommaSeparated}).
 * Tomcat silently drops a trailing empty entry and reads an empty one in the middle as the loopback address, so this
 * class is stricter: an empty entry, a leading or a trailing comma is rejected. The address part must be a literal IP
 * (IPv4 in canonical dotted decimal, IPv6 in hex groups), so no name is ever resolved. IPv4-mapped ranges
 * ({@code ::ffff:0:0/96}) fall outside every allowed block and are rejected.
 *
 * <p>Tomcat treats an empty value as "trust nobody" and an unset one as "trust every private range"; this class
 * rejects the empty value too, because production must name its proxy network explicitly (REQ-ABU-03).
 */
final class TrustedProxies {

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]+");
    private static final Pattern PREFIX = Pattern.compile("0|[1-9]\\d{0,2}");

    /** The private blocks an entry may lie in: RFC 1918, loopback, link-local and the IPv6 equivalents. */
    private static final List<Cidr> ALLOWED = List.of(cidr("10.0.0.0", 8), cidr("172.16.0.0", 12),
            cidr("192.168.0.0", 16), cidr("127.0.0.0", 8), cidr("169.254.0.0", 16), cidr("fc00::", 7),
            cidr("fe80::", 10), cidr("::1", 128));

    private TrustedProxies() {
    }

    static boolean isSafe(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            // The limit -1 keeps trailing empty entries, which String.split would drop.
            for (String entry : value.split(",", -1)) {
                if (!isInsideAllowedBlock(parse(entry.trim()))) {
                    return false;
                }
            }
            // Last word to Tomcat: if its own parser cannot read the value, the valve would fail at startup.
            NetMaskSet.parse(value);
            return true;
        } catch (IllegalArgumentException unreadable) {
            return false;
        }
    }

    private static boolean isInsideAllowedBlock(Cidr entry) {
        return ALLOWED.stream().anyMatch(block -> block.contains(entry));
    }

    private static Cidr parse(String entry) {
        int slash = entry.indexOf('/');
        if (slash <= 0 || entry.indexOf('/', slash + 1) >= 0) {
            throw new IllegalArgumentException("not an address/prefix pair");
        }
        String address = entry.substring(0, slash);
        String prefix = entry.substring(slash + 1);
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("prefix is not a plain number");
        }
        return new Cidr(addressBytes(address), Integer.parseInt(prefix));
    }

    /**
     * True only for a canonical dotted-decimal IPv4 (each octet 0-255) or a hex-group IPv6. The check runs BEFORE
     * {@link InetAddress#getByName}, which would otherwise treat a non-literal such as {@code 256.0.0.0} as a host name
     * and ask the resolver.
     */
    static boolean isLiteralAddress(String address) {
        return IPV4.matcher(address).matches() || (address.indexOf(':') >= 0 && IPV6.matcher(address).matches());
    }

    private static byte[] addressBytes(String address) {
        if (!isLiteralAddress(address)) {
            throw new IllegalArgumentException("not a literal IP address");
        }
        try {
            // Only a bounded dotted-decimal IPv4 or a hex-group IPv6 reaches this point; both are parsed as literals,
            // so the call never falls back to a host-name lookup.
            InetAddress parsed = InetAddress.getByName(address);
            if (address.indexOf(':') >= 0 && !(parsed instanceof Inet6Address)) {
                throw new IllegalArgumentException("IPv4-mapped form");
            }
            return parsed.getAddress();
        } catch (UnknownHostException unreadable) {
            throw new IllegalArgumentException("unreadable address", unreadable);
        }
    }

    private static Cidr cidr(String address, int prefix) {
        return new Cidr(addressBytes(address), prefix);
    }

    /** An address and a prefix length; the bits after the prefix are ignored, as in Tomcat's {@code NetMask}. */
    private record Cidr(byte[] address, int prefix) {

        Cidr {
            if (prefix > address.length * 8) {
                throw new IllegalArgumentException("prefix longer than the address");
            }
        }

        /** True when {@code other} is the same family, no wider than this block, and shares its leading bits. */
        boolean contains(Cidr other) {
            if (other.address.length != address.length || other.prefix < prefix) {
                return false;
            }
            int wholeBytes = prefix / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (address[i] != other.address[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            return rest == 0 || ((address[wholeBytes] ^ other.address[wholeBytes]) & (0xFF << (8 - rest)) & 0xFF) == 0;
        }
    }
}
