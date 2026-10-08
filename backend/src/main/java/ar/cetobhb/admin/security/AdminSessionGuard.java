package ar.cetobhb.admin.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * Fail-closed check of the administration session cookie, in every profile (REQ-AUTH-04, design AD-A9): the cookie must
 * be {@code Secure}, {@code HttpOnly} and {@code SameSite=Strict}, host-only (no {@code Domain}), named
 * {@code JSESSIONID}, and the session id may travel only in the cookie (no URL rewriting). The values are read with
 * Boot's own {@link Binder}, the same relaxed binding that feeds the servlet container, so a differently spelled
 * override (camelCase argument, environment variable, indexed list) is seen exactly as the container sees it. There is
 * no insecure profile: an unset, blank or any other value is refused, and the failure text is fixed and never repeats
 * the offending value.
 *
 * <p>{@link Lazy @Lazy(false)}: under {@code spring.main.lazy-initialization=true} Boot would otherwise defer this bean
 * and the check would never run. Logout expires the cookie by the literal name {@code JSESSIONID}, which is why the
 * name is pinned here instead of followed.
 *
 * <p>Tests of the full application do not override any of these: the {@code Secure} flag is honoured by hand in the
 * test clients (the JDK client ignores it on plain HTTP), which is how a real browser behaves behind the TLS proxy.
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
class AdminSessionGuard {

    static final String SECURE = "server.servlet.session.cookie.secure";
    static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";
    static final String SAME_SITE = "server.servlet.session.cookie.same-site";
    static final String TRACKING_MODES = "server.servlet.session.tracking-modes";
    static final String DOMAIN = "server.servlet.session.cookie.domain";
    static final String NAME = "server.servlet.session.cookie.name";

    /** The cookie name that logout expires. */
    static final String SESSION_COOKIE_NAME = "JSESSIONID";

    AdminSessionGuard(Environment environment) {
        Binder binder = Binder.get(environment);
        requireTrue(text(binder, SECURE), SECURE);
        requireTrue(text(binder, HTTP_ONLY), HTTP_ONLY);
        if (!"strict".equals(trimmedLowerCase(text(binder, SAME_SITE)))) {
            throw new IllegalStateException(SAME_SITE + " must be strict");
        }
        if (!isCookieOnly(binder)) {
            throw new IllegalStateException(TRACKING_MODES + " must be cookie");
        }
        String domain = text(binder, DOMAIN);
        if (domain != null && !domain.isBlank()) {
            throw new IllegalStateException(DOMAIN + " must be unset");
        }
        String name = text(binder, NAME);
        if (name != null && !SESSION_COOKIE_NAME.equals(name)) {
            throw new IllegalStateException(NAME + " must be " + SESSION_COOKIE_NAME);
        }
    }

    private static boolean isCookieOnly(Binder binder) {
        List<String> modes = binder.bind(TRACKING_MODES, Bindable.listOf(String.class)).orElse(List.of());
        Set<String> distinct = modes.stream().map(AdminSessionGuard::trimmedLowerCase).collect(Collectors.toSet());
        return distinct.equals(Set.of("cookie"));
    }

    private static String text(Binder binder, String property) {
        return binder.bind(property, Bindable.of(String.class)).orElse(null);
    }

    private static void requireTrue(String value, String property) {
        if (!"true".equals(trimmedLowerCase(value))) {
            throw new IllegalStateException(property + " must be true");
        }
    }

    private static String trimmedLowerCase(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
