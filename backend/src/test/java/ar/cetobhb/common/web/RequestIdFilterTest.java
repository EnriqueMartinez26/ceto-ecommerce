package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAUuidAndIgnoresTheInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "attacker-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader("X-Request-Id");
        assertThat(id).isNotEqualTo("attacker-value");
        assertThat(UUID.fromString(id)).hasToString(id);
        assertThat(request.getAttribute("requestId")).isEqualTo(id);
    }

    @Test
    void generatesADifferentIdPerRequest() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest(), second, new MockFilterChain());

        assertThat(first.getHeader("X-Request-Id")).isNotEqualTo(second.getHeader("X-Request-Id"));
    }

    @Test
    void mdcHoldsTheIdDuringTheChainAndIsClearedAfterwards() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> seen.set(MDC.get("requestId")));

        assertThat(seen.get()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void mdcIsClearedWhenTheChainThrows() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(response.getHeader("X-Request-Id")).isNotNull();
    }

    @Test
    void theHeaderIsSetBeforeTheChainRuns() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerInChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response,
                (req, res) -> headerInChain.set(response.getHeader("X-Request-Id")));

        assertThat(headerInChain.get()).isNotNull();
    }
}
