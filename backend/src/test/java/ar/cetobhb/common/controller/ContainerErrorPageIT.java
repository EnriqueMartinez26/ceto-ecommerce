package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tomcat answers a request line it refuses (an encoded slash, backslash or NUL) with its own error page before any
 * filter runs. That page must not name the server, its version or a stack trace, on public and admin paths alike, and
 * no {@code Server} header may appear. Observed on the real server on 2026-10-08 (Tomcat 11.0.24): the default page is
 * a bare "HTTP Status 400" heading with no report and no footer, and no {@code Server} header. This test pins that
 * behaviour so a container upgrade or a changed valve cannot start advertising the server.
 */
class ContainerErrorPageIT extends AbstractServerIT {

    static Stream<String> refusedPaths() {
        return Stream.of("/api/admin%2Fx", "/api/admin%5Cx", "/api/admin/x%00", "/api/store/products%2Fx",
                "/api/store/products%5Cx", "/api/store/products%00", "/api/checkout/orders%2Fx");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedPaths")
    void theContainerErrorPageNamesNeitherTheServerNorAVersionNorAStackTrace(String path) throws Exception {
        Raw reply = raw("GET", path, null, null);

        assertThat(reply.status()).isEqualTo(400);
        String body = reply.body().toLowerCase(Locale.ROOT);
        assertThat(body).doesNotContain("tomcat", "apache", "catalina", "stacktrace", "exception", "at org.", "at java.",
                "root cause", "<h3>");
        assertThat(body).doesNotContainPattern("\\d+\\.\\d+\\.\\d+");
        assertThat(reply.headers()).doesNotContainKeys("server", "x-powered-by");
        reply.headers().values().forEach(values -> values.forEach(value -> assertThat(value.toLowerCase(Locale.ROOT))
                .doesNotContain("tomcat", "apache", "coyote")));
    }
}
