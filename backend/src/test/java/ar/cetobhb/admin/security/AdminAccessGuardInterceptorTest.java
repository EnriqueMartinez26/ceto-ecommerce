package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ar.cetobhb.common.web.ProblemResponses;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Defense in depth behind the Security chain (design AD-A3, REQ-AUTH-11): an MVC interceptor that refuses any
 * {@code /api/admin/**} handler call that has no principal, using the same path matcher as the handlers. The Security
 * filter is NOT in these tests (standalone MockMvc), which is the point: they show what the guard does when the filter
 * mapping and the handler mapping disagree about a path.
 */
class AdminAccessGuardInterceptorTest {

    private static final AtomicInteger REACHED = new AtomicInteger();

    @RestController
    static class Handlers {
        @GetMapping("/api/admin/orders")
        String orders() {
            REACHED.incrementAndGet();
            return "orders";
        }

        @PutMapping("/api/admin/orders")
        String putOrders() {
            REACHED.incrementAndGet();
            return "put";
        }

        @GetMapping("/api/admin/session")
        String sessionGet() {
            REACHED.incrementAndGet();
            return "session";
        }

        @PostMapping("/api/admin/session")
        String sessionPost() {
            REACHED.incrementAndGet();
            return "session-post";
        }

        @GetMapping("/api/store/products")
        String store() {
            REACHED.incrementAndGet();
            return "store";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void build() {
        REACHED.set(0);
        AdminAccessGuardInterceptor guard = new AdminAccessGuardInterceptor(
                new ProblemResponses(JsonMapper.builder().build()));
        mvc = MockMvcBuilders.standaloneSetup(new Handlers())
                .addMappedInterceptors(new String[] {AdminAccessGuardInterceptor.PATH_PATTERN}, guard).build();
    }

    private static final Principal ADMIN = () -> "admin";

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void anAnonymousCallToAnAdminHandlerIs401UnauthenticatedAndTheHandlerNeverRuns() throws Exception {
        for (MockHttpServletRequestBuilder request : java.util.List.of(get("/api/admin/orders"),
                put("/api/admin/orders"), post("/api/admin/session"))) {
            MockHttpServletResponse response = send(request);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
        }
        assertThat(REACHED).hasValue(0);
    }

    @Test
    void aCallWithAPrincipalReachesTheHandler() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/orders").principal(ADMIN));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("orders");
        assertThat(send(put("/api/admin/orders").principal(ADMIN)).getStatus()).isEqualTo(200);
        assertThat(REACHED).hasValue(2);
    }

    @Test
    void theSessionBootstrapIsOpenToAnonymousGetButNotToOtherMethods() throws Exception {
        MockHttpServletResponse open = send(get("/api/admin/session"));

        assertThat(open.getStatus()).isEqualTo(200);
        assertThat(open.getContentAsString()).isEqualTo("session");
        assertThat(send(post("/api/admin/session")).getStatus()).isEqualTo(401);
        assertThat(REACHED).hasValue(1);
    }

    @Test
    void aPathOutsideTheAdminAreaIsNotGuarded() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(REACHED).hasValue(1);
    }

    @Test
    void theGuardOnlyChecksThatSomeonePassedSecurityNotWhoItIs() throws Exception {
        Principal anybody = () -> "someone-else-entirely";

        assertThat(send(get("/api/admin/orders").principal(anybody)).getStatus()).isEqualTo(200);
    }
}
