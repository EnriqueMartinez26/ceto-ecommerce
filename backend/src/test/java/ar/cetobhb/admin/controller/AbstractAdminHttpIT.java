package ar.cetobhb.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ar.cetobhb.support.AbstractHttpIT;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request helpers of the admin HTTP contract tests on MockMvc that do not need an order. Requests are authenticated with
 * {@code user()}, which installs its own security context repository, so these tests make no assertion about server
 * sessions (those live in the real-server ITs). Mutations carry the double-submit token written by hand: the
 * {@code csrf()} post processor is NOT used on purpose, because it swaps the CSRF token repository of the shared
 * MockMvc filter for a session-backed one for good and every later test of the same context that expects the XSRF
 * cookie would fail depending on the class order.
 */
abstract class AbstractAdminHttpIT extends AbstractHttpIT {

    protected static final String XSRF_TOKEN = "test-xsrf-token-0123456789";

    @Autowired
    protected JdbcTemplate jdbc;

    protected static MockHttpServletRequestBuilder adminGet(String path) {
        return get(path).with(user("admin"));
    }

    protected static MockHttpServletRequestBuilder adminPost(String path) {
        return withToken(post(path).with(user("admin")));
    }

    protected static MockHttpServletRequestBuilder adminPostJson(String path, String body) {
        return adminPost(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static MockHttpServletRequestBuilder adminPutJson(String path, String body) {
        return withToken(put(path).with(user("admin"))).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("__Host-XSRF-TOKEN", XSRF_TOKEN)).header("X-XSRF-TOKEN", XSRF_TOKEN);
    }

    protected int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    protected List<String> auditActions() {
        return jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class);
    }
}
