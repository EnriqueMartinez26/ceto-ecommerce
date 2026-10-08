package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.checkout.service.StorefrontService;
import ar.cetobhb.support.AbstractHttpIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The 500 case of the header contract: a service that blows up still answers with the hardening headers. */
class SecurityHeadersServerErrorIT extends AbstractHttpIT {

    @MockitoBean
    private StorefrontService storefront;

    @Test
    void anUnexpectedFailureAnswers500WithTheHeaders() throws Exception {
        when(storefront.listProducts()).thenThrow(new IllegalStateException("boom"));

        MockHttpServletResponse response = send(get("/api/store/products").accept(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
    }
}
