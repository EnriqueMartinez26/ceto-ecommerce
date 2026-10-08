package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import adminprobe.AdminProbeController;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real-server twin of {@link AdminAnonymousSessionIT} (review fix batch 5, item 6): the cookies an anonymous admin
 * request receives, as they travel on the wire.
 *
 * <p>Recorded from the real run: {@code csrf.spa()} renders the token cookie on every admin response, so an anonymous
 * read (401) and an anonymous mutation (refused by CSRF
 * with 403) each receive exactly one {@code XSRF-TOKEN} cookie (the SPA handler loads the deferred token on every
 * request), Secure, SameSite=Strict, Path=/ and readable by scripts (no HttpOnly), which is how the SPA
 * echoes it back. A server session cookie is never set.
 */
class AdminAnonymousSessionServerIT extends AbstractAdminProbeServerIT {

    record Case(String method, int status, boolean xsrfCookie) {
        @Override
        public String toString() {
            return method + " -> " + status;
        }
    }

    static Stream<Case> cases() {
        return List.of(new Case("GET", 401, true), new Case("POST", 403, true), new Case("PUT", 403, true),
                new Case("PATCH", 403, true), new Case("DELETE", 403, true)).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void anAnonymousRequestGetsNoSessionCookieAndOnlyTheDocumentedXsrfCookie(Case c) throws Exception {
        Raw reply = raw(c.method(), "/api/admin/probe", null, c.method().equals("GET") ? null : "{}");

        assertThat(reply.status()).isEqualTo(c.status());
        assertThat(reply.body()).doesNotContain(AdminProbeController.SENTINEL);
        List<String> cookies = reply.headers().getOrDefault("set-cookie", List.of());
        assertThat(cookies).noneMatch(cookie -> cookie.contains("JSESSIONID"));
        if (c.xsrfCookie()) {
            assertThat(cookies).hasSize(1);
            String cookie = cookies.get(0);
            assertThat(cookie).startsWith("XSRF-TOKEN=").contains("Secure").contains("SameSite=Strict")
                    .contains("Path=/").doesNotContain("HttpOnly");
        } else {
            assertThat(cookies).isEmpty();
        }
    }
}
