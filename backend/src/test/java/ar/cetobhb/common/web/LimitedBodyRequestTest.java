package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LimitedBodyRequestTest {

    private static LimitedBodyRequest request(String characterEncoding) {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/checkout/orders");
        raw.setContent("abc".getBytes(StandardCharsets.US_ASCII));
        raw.setCharacterEncoding(characterEncoding);
        return new LimitedBodyRequest(raw, 16);
    }

    @Test
    void anUnknownCharsetIsAnUnsupportedEncodingNotAnUncheckedFailure() {
        // Tomcat does the same: getReader throws UnsupportedEncodingException, which the advice maps to a 4xx.
        assertThatThrownBy(() -> request("no-such-charset").getReader())
                .isExactlyInstanceOf(UnsupportedEncodingException.class);
    }

    @Test
    void anIllegalCharsetNameIsAnUnsupportedEncodingToo() {
        assertThatThrownBy(() -> request("bad name!").getReader())
                .isExactlyInstanceOf(UnsupportedEncodingException.class);
    }

    @Test
    void theReaderDecodesWithTheDeclaredCharset() throws Exception {
        assertThat(request("UTF-8").getReader().readLine()).isEqualTo("abc");
    }

    @Test
    void askingForTheReaderAfterTheStreamIsAnIllegalStateAsInTheServletContract() throws Exception {
        LimitedBodyRequest request = request("UTF-8");
        request.getInputStream();

        assertThatThrownBy(request::getReader).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void askingForTheStreamAfterTheReaderIsAnIllegalStateAsInTheServletContract() throws Exception {
        LimitedBodyRequest request = request("UTF-8");
        request.getReader();

        assertThatThrownBy(request::getInputStream).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSameStreamOrReaderIsReturnedOnRepeatedCalls() throws Exception {
        LimitedBodyRequest streams = request("UTF-8");
        assertThat(streams.getInputStream()).isSameAs(streams.getInputStream());
        LimitedBodyRequest readers = request("UTF-8");
        assertThat(readers.getReader()).isSameAs(readers.getReader());
    }
}
