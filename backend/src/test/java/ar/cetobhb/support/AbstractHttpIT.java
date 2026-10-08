package ar.cetobhb.support;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for HTTP contract tests on the MOCK servlet environment. Every request gets its own remote address from the
 * benchmarking range 198.18.0.0/15, so a per-client limiter added later never interferes between requests.
 */
public abstract class AbstractHttpIT extends AbstractPostgresIT {

    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;

    protected MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return sendForResult(request).getResponse();
    }

    /** Like {@link #send} but keeps the MVC result, e.g. to assert which exception the framework resolved. */
    protected MvcResult sendForResult(MockHttpServletRequestBuilder request) throws Exception {
        int n = NEXT_CLIENT.getAndIncrement();
        request.with(r -> {
            r.setRemoteAddr("198." + (18 + ((n >> 16) & 0x01)) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff));
            return r;
        });
        return mvc.perform(request).andReturn();
    }
}
