package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spike U-3 on a real Tomcat (REQ-AUTH-06): the container itself holds the Security filter for {@code /api/admin/*}
 * only, public requests are answered without a Security header or cookie, and an admin request is stopped by the chain
 * (401) before any handler is looked up.
 */
class SecurityFilterScopeServerIT extends AbstractServerIT {

    @Autowired
    private ServletContext servletContext;

    @Test
    void tomcatMapsTheSecurityFilterToTheAdminPathOnly() {
        FilterRegistration registration = servletContext.getFilterRegistration("springSecurityFilterChain");

        assertThat(registration).isNotNull();
        assertThat(registration.getUrlPatternMappings()).containsExactly("/api/admin/*");
    }

    @Test
    void anonymousAdminRequestsGet401WithoutASessionCookieOrSecurityDefaultsOnPublicRoutes() throws Exception {
        Raw admin = raw("GET", "/api/admin/anything", null, null);
        Raw adminPost = raw("POST", "/api/admin/anything", null, "{}");
        Raw publicRead = raw("GET", "/api/store/products", null, null);

        assertThat(admin.status()).isEqualTo(401);
        assertThat(admin.headers().getOrDefault("set-cookie", java.util.List.of()))
                .noneMatch(cookie -> cookie.contains("JSESSIONID"));
        // The CSRF token lives in a cookie (csrf.spa()), so an anonymous POST opens no session; the cookies of every
        // anonymous method are pinned in AdminAnonymousSessionServerIT.
        assertThat(adminPost.status()).isEqualTo(403);
        assertThat(publicRead.status()).isEqualTo(200);
        assertThat(publicRead.headers()).doesNotContainKeys("x-frame-options", "x-xss-protection", "pragma",
                "set-cookie");
    }
}
