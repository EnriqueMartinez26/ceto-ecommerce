package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The trusted-proxies allowlist is CIDR-only and every entry must lie fully inside a private range, checked by prefix
 * arithmetic (no sampling of public addresses, no regex). Whitespace around an entry is accepted because Tomcat trims
 * it; an empty entry or a trailing comma is rejected, although Tomcat would drop the trailing one silently.
 */
class TrustedProxiesTest {

    @ParameterizedTest(name = "[{0}] is accepted")
    @ValueSource(strings = {"10.0.0.0/8", "10.1.2.3/32", "172.16.0.0/12", "172.18.0.0/16", "172.31.255.255/32",
        "192.168.0.0/16", "192.168.1.0/24", "127.0.0.0/8", "127.0.0.1/32", "169.254.0.0/16", "fc00::/7", "fd00::/8",
        "fdff::1/128", "fe80::/10", "fe80::1/128", "febf::/16", "::1/128", "172.16.0.0/12, 192.168.0.0/16",
        "127.0.0.1/32,::1/128", " 10.0.0.0/8 ", "10.0.0.0/8 ,\t127.0.0.1/32", "FD00::/8"})
    void acceptsEntriesFullyInsideAPrivateRange(String value) {
        assertThat(TrustedProxies.isSafe(value)).isTrue();
    }

    @ParameterizedTest(name = "[{0}] is rejected")
    @ValueSource(strings = {
        // Ranges that reach outside the private blocks (the old sampling heuristic let several of these through)
        "172.0.0.0/8", "172.16.0.0/11", "172.32.0.0/16", "192.0.0.0/8", "192.168.0.0/15", "100.0.0.0/8",
        "100.64.0.0/10", "11.0.0.0/8", "9.0.0.0/8", "10.0.0.0/7", "127.0.0.0/7", "0.0.0.0/0", "128.0.0.0/1",
        "169.254.0.0/15", "::/0", "2000::/3", "2400::/12", "2001:db8::/32", "fc00::/6", "fe80::/9", "::/128",
        "::1/127", "::/1", "8000::/1",
        // IPv4-mapped and IPv4-compatible forms
        "::ffff:0:0/96", "::ffff:10.0.0.0/104", "::ffff:a00:0/104", "::ffff:127.0.0.1/128", "::10.0.0.1/128",
        // One bad entry spoils the list
        "10.0.0.0/8,0.0.0.0/0", "10.0.0.0/8,172.0.0.0/8", "10.0.0.0/8,2400::/12",
        // Empty entries and trailing commas
        "10.0.0.0/8,", ",10.0.0.0/8", "10.0.0.0/8,,127.0.0.1/32", "10.0.0.0/8, ", ",",
        // Not a CIDR list at all: regex forms, bare addresses, hostnames, port syntax, garbage
        ".*", ".+", "172\\..*", "127\\.0\\.0\\.1", "10\\.0\\.0\\.\\d+", "172\\.(1[6-9]|2\\d|3[01])\\..*",
        "0000:0000:0000:0000:0000:0000:0000:0001", "(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}", "(unclosed",
        "127.0.0.1", "::1", "localhost", "localhost/8", "10.0.0.0/8|0.0.0.0/0", "10.0.0.0/8|.*",
        "10.0.0.0/8 127.0.0.1/32", "10.0.0.0/8;80", "[::1]/128", "not/a-cidr", "/8", "10.0.0.0/", "10.0.0.0/-1",
        "10.0.0.0/+8", "10.0.0.0/8x", "10.0.0.0/08", "10.0.0.0/33", "fd00::/129", "10.0.0/8", "10.0.0.0.0/8",
        "010.0.0.0/8", "10.0.0.256/32", "fe80::%eth0/10", "fd00::g/8", "fd00:::1/16", "10.0.0.0/8/8"})
    void rejectsAnythingThatIsNotAPrivateCidr(String value) {
        assertThat(TrustedProxies.isSafe(value)).isFalse();
    }

    @ParameterizedTest(name = "[{0}] is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingAndBlankValues(String value) {
        assertThat(TrustedProxies.isSafe(value)).isFalse();
    }

    @Test
    void theAllowedBlocksThemselvesAreAccepted() {
        for (String block : new String[] {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
            "169.254.0.0/16", "fc00::/7", "fe80::/10", "::1/128"}) {
            assertThat(TrustedProxies.isSafe(block)).as(block).isTrue();
        }
    }
}
