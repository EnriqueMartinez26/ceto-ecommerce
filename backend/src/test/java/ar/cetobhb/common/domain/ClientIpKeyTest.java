package ar.cetobhb.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Bucket keys for client addresses: IPv4 per address, IPv6 per /64, anything else in one shared bucket. */
class ClientIpKeyTest {

    private static final String INVALID = "invalid";

    @Test
    void anIpv4AddressIsKeyedByItself() {
        assertThat(ClientIpKey.of("203.0.113.7")).isEqualTo("v4:203.0.113.7");
        assertThat(ClientIpKey.of("0.0.0.0")).isEqualTo("v4:0.0.0.0");
        assertThat(ClientIpKey.of("255.255.255.255")).isEqualTo("v4:255.255.255.255");
    }

    @Test
    void neighbouringIpv4AddressesGetDifferentKeys() {
        assertThat(ClientIpKey.of("203.0.113.7")).isNotEqualTo(ClientIpKey.of("203.0.113.8"));
    }

    @Test
    void addressesOfTheSameIpv6Slash64ShareOneKey() {
        String key = ClientIpKey.of("2001:db8:1:2:aaaa:bbbb:cccc:dddd");

        assertThat(key).isEqualTo("v6:20010db800010002");
        assertThat(ClientIpKey.of("2001:db8:1:2::1")).isEqualTo(key);
        assertThat(ClientIpKey.of("2001:DB8:1:2:FFFF:FFFF:FFFF:FFFF")).isEqualTo(key);
    }

    @Test
    void addressesOfDifferentIpv6Slash64sGetDifferentKeys() {
        assertThat(ClientIpKey.of("2001:db8:1:2::1")).isNotEqualTo(ClientIpKey.of("2001:db8:1:3::1"));
        assertThat(ClientIpKey.of("2001:db8:1:2::1")).isNotEqualTo(ClientIpKey.of("2001:db8:2:2::1"));
        assertThat(ClientIpKey.of("2001:db8:1:2::1")).isNotEqualTo(ClientIpKey.of("2001:db9:1:2::1"));
    }

    @Test
    void theUncompressedLoopbackThatTomcatReportsAndItsCompressedFormShareAKey() {
        assertThat(ClientIpKey.of("0:0:0:0:0:0:0:1")).isEqualTo("v6:0000000000000000");
        assertThat(ClientIpKey.of("::1")).isEqualTo("v6:0000000000000000");
        assertThat(ClientIpKey.of("::")).isEqualTo("v6:0000000000000000");
    }

    @Test
    void anIpv4MappedAddressIsKeyedLikeTheIpv4AddressInEveryNotation() {
        assertThat(ClientIpKey.of("::ffff:203.0.113.7")).isEqualTo("v4:203.0.113.7");
        assertThat(ClientIpKey.of("::FFFF:cb00:7107")).isEqualTo("v4:203.0.113.7");
        assertThat(ClientIpKey.of("0:0:0:0:0:ffff:cb00:7107")).isEqualTo("v4:203.0.113.7");
    }

    @Test
    void aZoneIdIsIgnored() {
        assertThat(ClientIpKey.of("fe80::1%eth0")).isEqualTo(ClientIpKey.of("fe80::1"));
        assertThat(ClientIpKey.of("fe80::1%12")).isEqualTo("v6:fe80000000000000");
    }

    @Test
    void anythingThatIsNotAnIpLiteralSharesTheInvalidKeyWithoutAnyLookup() {
        List<String> notLiterals = List.of("", " ", "localhost", "example.com", "abc", "1.2.3", "1.2.3.4.5",
                "1.2.3.256", "256.1.1.1", "1.2.3.-4", "01.2.3.4", "1.2.3.04", "1..2.3", "1.2.3.4 ", " 1.2.3.4",
                "12345", "0x7f.0.0.1", "2130706433", "1.2.3.4%eth0", "[::1]", "::g", "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7", ":::", "1::2::3", "12345::1", "::ffff:1.2.3.256", "::1.2.3.4.5",
                "2001:db8::1/64", "1.2.3.4:80", "a".repeat(300), "1.2.3.٤", "::١");

        for (String notLiteral : notLiterals) {
            assertThat(ClientIpKey.of(notLiteral)).as(notLiteral).isEqualTo(INVALID);
        }
        assertThat(ClientIpKey.of(null)).isEqualTo(INVALID);
    }

    @Test
    void theTextLengthIsBoundedAt64CharactersAndTheZoneIdIsRestrictedToSafeCharacters() {
        assertThat(ClientIpKey.of("fe80::1%" + "a".repeat(56))).isEqualTo("v6:fe80000000000000");
        assertThat(ClientIpKey.of("fe80::1%" + "a".repeat(57))).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%eth 0")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%eth0/1")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%Eth_0.1-x")).isEqualTo("v6:fe80000000000000");
    }

    @Test
    void everyCharacterClassBoundaryOfTheZoneIdIsHonoured() {
        assertThat(ClientIpKey.of("fe80::1%azAZ09")).isEqualTo("v6:fe80000000000000");
        assertThat(ClientIpKey.of("fe80::1%`")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%{")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%@")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("fe80::1%[")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("%eth0")).isEqualTo(INVALID);
    }

    @Test
    void everyHexDigitAndDecimalDigitBoundaryIsHonoured() {
        assertThat(ClientIpKey.of("90.9.0.10")).isEqualTo("v4:90.9.0.10");
        assertThat(ClientIpKey.of("1.2.3./")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("1.2.3.:")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("0123:4567:89ab:cdef:ABCD:EF01:2345:6789")).isEqualTo("v6:0123456789abcdef");
        assertThat(ClientIpKey.of("a:f:A:F::1")).isEqualTo("v6:000a000f000a000f");
        for (String bad : List.of("::/", "::`", "::g", "::G", "::@", "::٠", "::Ａ", "::０")) {
            assertThat(ClientIpKey.of(bad)).as(bad).isEqualTo(INVALID);
        }
    }

    @Test
    void theDoubleColonStandsForAtLeastOneGroupAndNeverForNone() {
        assertThat(ClientIpKey.of("1:2:3:4:5:6:7::")).isEqualTo("v6:0001000200030004");
        assertThat(ClientIpKey.of("::2:3:4:5:6:7:8")).isEqualTo("v6:0000000200030004");
        assertThat(ClientIpKey.of("1:2:3::5:6:7:8")).isEqualTo("v6:0001000200030000");
        assertThat(ClientIpKey.of("1:2:3:4::5:6:7:8")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("1:2:3:4:5:6:7:8::")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("::1:2:3:4:5:6:7:8")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("1:2:3:4:5:6:7:8:9::")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("::1:2:3:4:5:6:7:8:9")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("1:2:3:4:5:6:7:8:9")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of("1:2:3:4:5:6:7:")).isEqualTo(INVALID);
        assertThat(ClientIpKey.of(":2:3:4:5:6:7:8")).isEqualTo(INVALID);
    }

    @Test
    void aFullyExpandedIpv6AtTheLongestLegalLengthIsAccepted() {
        assertThat(ClientIpKey.of("2001:0db8:0001:0002:0003:0004:0005:0006")).isEqualTo("v6:20010db800010002");
        assertThat(ClientIpKey.of("1:2:3:4:5:6:1.2.3.4")).isEqualTo("v6:0001000200030004");
    }
}
