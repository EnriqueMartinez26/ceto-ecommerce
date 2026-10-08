package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The headers are set before the chain runs, so they cannot be lost by whatever the chain does next. */
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private static void assertHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void addsThePolicyHeadersToAnOrdinaryResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertHeaders(response);
    }

    @Test
    void theHeadersAreAlreadyThereWhenTheChainStartsAndSurviveAFailingChain() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res)
                    throws ServletException {
                assertHeaders((MockHttpServletResponse) res);
                throw new ServletException("downstream failure");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, failing))
                .isInstanceOf(ServletException.class);

        assertHeaders(response);
    }
}
