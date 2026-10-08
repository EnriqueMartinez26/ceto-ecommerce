package ar.cetobhb.admin.security;

import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fail-closed check of the administration session cookie, in every profile (REQ-AUTH-04, design AD-A9): the cookie must
 * be {@code Secure}, {@code HttpOnly} and {@code SameSite=Strict}, and the session id may travel only in the cookie
 * (no URL rewriting). Like {@code WebConfig} it reads the effective {@link Environment}, so a command-line argument or
 * an environment variable that weakens a setting of {@code application.yml} stops the startup as well. There is no
 * insecure profile: an unset, blank or any other value is refused, and the failure text is fixed and never repeats the
 * offending value.
 *
 * <p>Tests of the full application do not override any of these: the {@code Secure} flag is honoured by hand in the
 * test clients (the JDK client ignores it on plain HTTP), which is how a real browser behaves behind the TLS proxy.
 */
@Configuration(proxyBeanMethods = false)
class AdminSessionGuard {

    static final String SECURE = "server.servlet.session.cookie.secure";
    static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";
    static final String SAME_SITE = "server.servlet.session.cookie.same-site";
    static final String TRACKING_MODES = "server.servlet.session.tracking-modes";

    AdminSessionGuard(Environment environment) {
        requireTrue(environment, SECURE);
        requireTrue(environment, HTTP_ONLY);
        if (!"strict".equals(trimmedLowerCase(environment.getProperty(SAME_SITE)))) {
            throw new IllegalStateException(SAME_SITE + " must be strict");
        }
        if (!"cookie".equals(trimmedLowerCase(environment.getProperty(TRACKING_MODES)))) {
            throw new IllegalStateException(TRACKING_MODES + " must be cookie");
        }
    }

    private static void requireTrue(Environment environment, String property) {
        if (!"true".equals(trimmedLowerCase(environment.getProperty(property)))) {
            throw new IllegalStateException(property + " must be true");
        }
    }

    private static String trimmedLowerCase(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
