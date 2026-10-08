package ar.cetobhb.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.orders.config.OrderProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * The wire DTOs carry absolute structural caps and {@link OrderProperties} carries the configurable business
 * limits. Both ends are bounded by the same numbers on purpose; if one moves without the other this test fails.
 */
class CheckoutDtoCapsTest {

    @Test
    void theQuantityCapOfALineEqualsTheMaxUnitsPerLineBound() throws Exception {
        long dtoCap = annotation(LineRequest.class, "quantity", Max.class).value();
        long propertiesBound = annotation(OrderProperties.class, "maxUnitsPerLine", Max.class).value();

        assertThat(dtoCap).isEqualTo(propertiesBound);
    }

    @Test
    void theLineCountCapOfTheRequestEqualsTheMaxLinesPerOrderBound() throws Exception {
        int dtoCap = annotation(PlaceOrderRequest.class, "lines", Size.class).max();
        long propertiesBound = annotation(OrderProperties.class, "maxLinesPerOrder", Max.class).value();

        assertThat((long) dtoCap).isEqualTo(propertiesBound);
    }

    private static <A extends java.lang.annotation.Annotation> A annotation(Class<?> type, String component,
            Class<A> annotation) throws NoSuchFieldException {
        Field field = type.getDeclaredField(component);
        A found = field.getAnnotation(annotation);
        assertThat(found).as("@%s on %s.%s", annotation.getSimpleName(), type.getSimpleName(), component)
                .isNotNull();
        return found;
    }
}
