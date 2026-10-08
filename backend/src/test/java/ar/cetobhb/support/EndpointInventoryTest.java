package ar.cetobhb.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The inventory support code turns a mapped pattern into a concrete anonymous call. A pattern it cannot turn into one
 * path must fail loudly: a silently invalid path would make the generated 401 test check nothing.
 */
class EndpointInventoryTest {

    private static EndpointInventory.Mapped route(String method, String pattern) {
        return new EndpointInventory.Mapped(method, pattern, Object.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "/api/admin/orders|/api/admin/orders",
            "/api/admin/orders/{id}|/api/admin/orders/1",
            "/api/admin/orders/{id}/lines/{line}|/api/admin/orders/1/lines/1",
            "/api/admin/orders/{id:\\d+}|/api/admin/orders/1",
            "/api/admin/orders/{id:\\d{3}}|/api/admin/orders/1",
            "/api/admin/{kind:[a-z]+}/x/{id:[0-9]{1,4}}|/api/admin/1/x/1",
            "/api/admin/{name:.+}|/api/admin/1"})
    void pathVariablesWithOrWithoutARegexBecomeOne(String pattern, String expected) {
        assertThat(EndpointInventory.concretePath(route("GET", pattern))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/admin/**", "/api/admin/*", "/api/admin/{*rest}", "/api/adm?n/x", "/api/**/x",
            "/api/admin/{unclosed", "/api/admin/{id:\\d{3}", "/api/admin/{}"})
    void anUnsupportedPatternFailsLoudlyInsteadOfProducingAnInvalidPath(String pattern) {
        assertThatThrownBy(() -> EndpointInventory.concretePath(route("GET", pattern)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(pattern);
    }

    @Test
    void aRouteWithoutAMethodIsCalledWithEveryMethodAClientCouldUse() {
        List<String> methods = EndpointInventory.anonymousCalls(route("ANY", "/api/admin/x")).stream()
                .map(EndpointInventoryTest::methodOf).toList();

        assertThat(methods).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    }

    @Test
    void aRouteWithAMethodIsCalledWithThatMethodOnly() {
        assertThat(EndpointInventory.anonymousCalls(route("PUT", "/api/admin/x")).stream()
                .map(EndpointInventoryTest::methodOf)).containsExactly("PUT");
    }

    private static String methodOf(MockHttpServletRequestBuilder call) {
        return call.buildRequest(new MockServletContext()).getMethod();
    }
}
