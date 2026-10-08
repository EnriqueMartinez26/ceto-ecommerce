package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The filter in isolation: decisions on the declared length, and the wrapper handed to the chain otherwise. */
class BodySizeLimitFilterTest {

    private static final int MAX = 1024;
    private final BodySizeLimitFilter filter =
            new BodySizeLimitFilter(new ProblemResponses(JsonMapper.builder().build()), MAX);

    /** A request whose body length is unknown to the container, like a chunked one. */
    private static final class ChunkedRequest extends MockHttpServletRequest {
        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }

    private static MockHttpServletRequest post(MockHttpServletRequest request, int bodySize) {
        request.setMethod("POST");
        request.setRequestURI("/api/checkout/orders");
        request.setContent(new byte[bodySize]);
        return request;
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRejectedWithoutReadingOrCallingTheChain() throws Exception {
        MockHttpServletRequest request = post(new MockHttpServletRequest(), MAX + 1);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "11111111-1111-1111-1111-111111111111");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain never called").isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"PAYLOAD_TOO_LARGE\"", "\"instance\":\"/api/checkout/orders\"",
                "\"requestId\":\"11111111-1111-1111-1111-111111111111\"");
    }

    @Test
    void aDeclaredLengthExactlyAtTheLimitPasses() throws Exception {
        MockHttpServletRequest request = post(new MockHttpServletRequest(), MAX);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void anUnknownLengthBodyIsCountedWhileTheChainReadsIt() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> readBeforeFailure = new AtomicReference<>(0);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                super.doFilter(request, response);
                try {
                    readBeforeFailure.set(request.getInputStream().readAllBytes().length);
                } catch (IOException e) {
                    failure.set(e);
                }
            }
        };

        filter.doFilter(post(new ChunkedRequest(), MAX + 500), new MockHttpServletResponse(), chain);

        assertThat(failure.get()).isInstanceOf(BodyTooLargeException.class);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anUnknownLengthBodyWithinTheLimitIsReadWhole() throws Exception {
        AtomicReference<Integer> read = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                super.doFilter(request, response);
                read.set(request.getInputStream().readAllBytes().length);
            }
        };

        filter.doFilter(post(new ChunkedRequest(), MAX), new MockHttpServletResponse(), chain);

        assertThat(read.get()).isEqualTo(MAX);
    }

    @Test
    void theReaderIsLimitedToo() throws Exception {
        AtomicBoolean failed = new AtomicBoolean();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                super.doFilter(request, response);
                try (var reader = request.getReader()) {
                    reader.transferTo(java.io.Writer.nullWriter());
                } catch (BodyTooLargeException e) {
                    failed.set(true);
                }
            }
        };
        ChunkedRequest request = (ChunkedRequest) post(new ChunkedRequest(), MAX + 1);
        request.setCharacterEncoding("UTF-8");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(failed).isTrue();
    }

    @Test
    void theChainReceivesAWrapperAndOtherRequestPropertiesAreUntouched() throws Exception {
        MockHttpServletRequest request = post(new ChunkedRequest(), 1);
        request.addHeader("Idempotency-Key", "abc");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest seen = (HttpServletRequest) chain.getRequest();
        assertThat(seen).isInstanceOf(LimitedBodyRequest.class).isNotSameAs(request);
        assertThat(seen.getHeader("Idempotency-Key")).isEqualTo("abc");
        assertThat(seen.getMethod()).isEqualTo("POST");
    }

    @Test
    void aNonPositiveLimitIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new BodySizeLimitFilter(new ProblemResponses(JsonMapper.builder().build()), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
