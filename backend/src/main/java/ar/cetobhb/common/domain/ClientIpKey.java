package ar.cetobhb.common.domain;

/**
 * Rate-limit bucket key of a client address (REQ-ABU-02): an IPv4 address is keyed by itself (/32), an IPv6 address
 * by its first 64 bits (/64, so one subscriber rotating inside its prefix keeps one bucket), an IPv4-mapped IPv6
 * address like the IPv4 one. Anything that is not a literal address maps to the single {@value #INVALID} key.
 *
 * <p>The text is parsed by hand and never handed to {@code InetAddress.getByName}, which falls back to a DNS lookup
 * for text it cannot read as a literal: this class never touches the network. The input is the container's
 * {@code getRemoteAddr()}, already resolved from trusted proxies; it is never logged here (Ley 25.326).
 */
public final class ClientIpKey {

    /** Shared bucket key of every input that is not a literal IP address. */
    public static final String INVALID = "invalid";

    private static final int MAX_LENGTH = 64;
    private static final int V6_GROUPS = 8;
    private static final int V4_PARTS = 4;
    private static final int MAPPED_PREFIX_GROUPS = 5;
    private static final int MAPPED_MARKER = 0xffff;
    private static final int MAX_GROUP_DIGITS = 4;
    private static final int MAX_OCTET_DIGITS = 3;
    private static final int MAX_OCTET = 255;

    private ClientIpKey() {
    }

    public static String of(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.length() > MAX_LENGTH) {
            return INVALID;
        }
        String address = remoteAddr;
        if (remoteAddr.contains("%")) {
            int zone = remoteAddr.indexOf('%');
            address = remoteAddr.substring(0, zone);
            if (!address.contains(":") || !isZoneId(remoteAddr.substring(zone + 1))) {
                return INVALID;
            }
        }
        if (address.contains(":")) {
            int[] groups = parseV6(address);
            return groups == null ? INVALID : v6Key(groups);
        }
        int[] octets = parseV4(address);
        return octets == null ? INVALID : v4Key(octets);
    }

    private static String v4Key(int[] octets) {
        return "v4:" + octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private static String v6Key(int[] groups) {
        boolean mapped = groups[MAPPED_PREFIX_GROUPS] == MAPPED_MARKER;
        for (int i = 0; i < MAPPED_PREFIX_GROUPS && mapped; i++) {
            mapped = groups[i] == 0;
        }
        if (mapped) {
            return v4Key(new int[] {groups[6] >> 8, groups[6] & MAX_OCTET, groups[7] >> 8, groups[7] & MAX_OCTET});
        }
        return String.format("v6:%04x%04x%04x%04x", groups[0], groups[1], groups[2], groups[3]);
    }

    private static boolean isZoneId(String zone) {
        if (zone.isEmpty()) {
            return false;
        }
        for (int i = 0; i < zone.length(); i++) {
            char c = zone.charAt(i);
            boolean safe = isAsciiDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '_' || c == '-';
            if (!safe) {
                return false;
            }
        }
        return true;
    }

    /** Dotted decimal, exactly four parts of 1 to 3 ASCII digits, no leading zeros, each at most 255; else null. */
    private static int[] parseV4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != V4_PARTS) {
            return null;
        }
        int[] octets = new int[V4_PARTS];
        for (int i = 0; i < V4_PARTS; i++) {
            String part = parts[i];
            boolean leadingZero = part.length() > 1 && part.charAt(0) == '0';
            if (part.isEmpty() || part.length() > MAX_OCTET_DIGITS || leadingZero || !allDigits(part)) {
                return null;
            }
            octets[i] = Integer.parseInt(part);
            if (octets[i] > MAX_OCTET) {
                return null;
            }
        }
        return octets;
    }

    /** Eight 16-bit groups of an IPv6 literal (compressed with one "::" and/or ending in IPv4); else null. */
    private static int[] parseV6(String text) {
        String hexText = text;
        int lastColon = text.lastIndexOf(':');
        String tail = text.substring(lastColon + 1);
        if (tail.contains(".")) {
            int[] octets = parseV4(tail);
            if (octets == null) {
                return null;
            }
            hexText = text.substring(0, lastColon + 1)
                    + Integer.toHexString(octets[0] << 8 | octets[1]) + ":"
                    + Integer.toHexString(octets[2] << 8 | octets[3]);
        }
        int compression = hexText.indexOf("::");
        if (compression != hexText.lastIndexOf("::")) {
            return null;
        }
        int[] groups = new int[V6_GROUPS];
        if (compression < 0) {
            return fill(groups, hexText) == V6_GROUPS ? groups : null;
        }
        int left = fill(groups, hexText.substring(0, compression));
        String rightText = hexText.substring(compression + 2);
        int[] rightGroups = new int[V6_GROUPS];
        int right = fill(rightGroups, rightText);
        if (left < 0 || right < 0 || left + right > V6_GROUPS - 1) {
            return null;
        }
        System.arraycopy(rightGroups, 0, groups, V6_GROUPS - right, right);
        return groups;
    }

    /** Parses colon-separated hex groups into {@code groups}; returns their count, -1 if invalid. */
    private static int fill(int[] groups, String text) {
        if (text.isEmpty()) {
            return 0;
        }
        String[] parts = text.split(":", -1);
        if (parts.length > groups.length) {
            return -1;
        }
        for (int i = 0; i < parts.length; i++) {
            int value = hex(parts[i]);
            if (value < 0) {
                return -1;
            }
            groups[i] = value;
        }
        return parts.length;
    }

    private static int hex(String group) {
        if (group.isEmpty() || group.length() > MAX_GROUP_DIGITS) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < group.length(); i++) {
            int digit = hexDigit(group.charAt(i));
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    private static int hexDigit(char c) {
        if (isAsciiDigit(c)) {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean allDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
