package ar.cetobhb.common.web;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hardening headers on every kind of response (REQ-HRD headers): successes, errors written by the advice (400, 404,
 * 415), errors written by a filter (413) and by the interceptor (429), and the absence of any CORS grant. The 500 case
 * needs a failing service and lives in {@code SecurityHeadersServerErrorIT}.
 */
class SecurityHeadersHttpIT extends AbstractHttpIT {

    private static final String CHECKOUT = "/api/checkout/orders";
    private static final String BODY = """
            {"buyer":{"name":"Maria Perez","phone":"+5493515550000","address":"Calle Falsa 123",
                      "zone":"RESTO_DEL_PAIS"},
             "lines":[{"productId":%d,"quantity":1}]}
            """;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    private static void assertSecurityHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Content-Type-Options")).as("nosniff").isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).as("referrer").isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).as("csp")
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).as("no CORS grant").isNull();
    }

    private static MockHttpServletRequestBuilder checkout(String body, String key) {
        return post(CHECKOUT).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aStorefrontListAnswers200WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products").accept(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);
        assertSecurityHeaders(response);
    }

    @Test
    void aCreatedOrderAnswers201WithTheHeaders() throws Exception {
        long id = catalog.createBase(product("Solid shampoo", "100.00")).id();
        inventory.createStock(id, 5);

        MockHttpServletResponse response = send(checkout(BODY.formatted(id), UUID.randomUUID().toString()));

        assertThat(response.getStatus()).isEqualTo(201);
        assertSecurityHeaders(response);
    }

    @Test
    void aMalformedBodyAnswers400WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(checkout("{", UUID.randomUUID().toString()));

        assertThat(response.getStatus()).isEqualTo(400);
        assertSecurityHeaders(response);
    }

    @Test
    void anUnknownProductAnswers404WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products/999999").accept(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(404);
        assertSecurityHeaders(response);
    }

    @Test
    void anUnknownPathAnswers404WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(get("/nope"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertSecurityHeaders(response);
    }

    @Test
    void anOversizedBodyWrittenByTheFilterAnswers413WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(checkout("a".repeat(20_000), UUID.randomUUID().toString()));

        assertThat(response.getStatus()).isEqualTo(413);
        assertSecurityHeaders(response);
    }

    @Test
    void anUnsupportedContentTypeAnswers415WithTheHeaders() throws Exception {
        MockHttpServletResponse response = send(post(CHECKOUT).contentType(MediaType.TEXT_PLAIN)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("hello"));

        assertThat(response.getStatus()).isEqualTo(415);
        assertSecurityHeaders(response);
    }

    @Test
    void aRateLimitedRequestWrittenByTheInterceptorAnswers429WithTheHeaders() throws Exception {
        MockHttpServletResponse last = null;
        for (int i = 0; i < 11; i++) {
            last = mvc.perform(checkout("{", UUID.randomUUID().toString()).with(request -> {
                request.setRemoteAddr("203.0.113.90");
                return request;
            })).andReturn().getResponse();
        }

        assertThat(last.getStatus()).isEqualTo(429);
        assertSecurityHeaders(last);
    }

    @Test
    void aPreflightRequestIsNotGrantedAnyCrossOriginAccess() throws Exception {
        MockHttpServletResponse response = send(options(CHECKOUT).header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"));

        assertSecurityHeaders(response);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isNull();
    }

    @Test
    void aRequestWithAnOriginHeaderGetsNoAllowOriginBack() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products").header("Origin", "https://evil.example")
                .accept(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);
        assertSecurityHeaders(response);
    }
}
