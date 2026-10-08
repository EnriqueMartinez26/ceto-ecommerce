package ar.cetobhb.common.config;

import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.catalina.util.NetMaskSet;

/**
 * Safety check of the value given to Tomcat's {@code RemoteIpValve} {@code internal-proxies} (verification U-6),
 * parsed with the SAME rules as the valve: a value containing {@code /} is a comma-separated CIDR list, anything else
 * is ONE regex that must match the whole address text. A peer matching it may set the client address through
 * {@code X-Forwarded-For}, so a value that would match a public address lets any caller forge its rate-limit key.
 *
 * <p>Tomcat treats an empty value as "trust nobody" and an unset one as "trust every private range"; this class
 * rejects the empty value too, because production must name its proxy network explicitly (REQ-ABU-03).
 *
 * <p>A value is safe when it parses AND matches none of a spread of public sample addresses. That cannot prove every
 * regex harmless, but it rejects every trust-all form ({@code .*}, {@code 0.0.0.0/0}, {@code ::/0}) and the usual
 * mistakes (a half-open CIDR, an unanchored-looking digit pattern).
 */
final class TrustedProxies {

    private static final List<String> PUBLIC_SAMPLES = List.of("1.1.1.1", "8.8.8.8", "34.120.0.1", "52.0.0.1",
            "93.184.216.34", "151.101.1.1", "185.199.108.153", "203.0.113.10", "2001:db8::1", "2606:4700::1111",
            "2a00:1450::1");

    private TrustedProxies() {
    }

    static boolean isSafe(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            if (value.indexOf('/') > 0) {
                NetMaskSet cidrs = NetMaskSet.parse(value);
                for (String sample : PUBLIC_SAMPLES) {
                    if (cidrs.contains(sample)) {
                        return false;
                    }
                }
                return true;
            }
            Pattern regex = Pattern.compile(value);
            return PUBLIC_SAMPLES.stream().noneMatch(sample -> regex.matcher(sample).matches());
        } catch (IllegalArgumentException | UnknownHostException unreadable) {
            return false;
        }
    }
}
