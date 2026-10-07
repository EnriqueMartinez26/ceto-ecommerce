package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The client sends ids and quantities only: prices and totals come from the database (CLAUDE.md rule 7). */
class PlaceOrderCommandContractTest {

    private static final Set<String> FORBIDDEN = Set.of("price", "unitprice", "total", "subtotal", "amount");

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void commandAndItsPartsExposeNoPriceOrTotal() {
        for (Class<? extends Record> type : List.of(
                PlaceOrderCommand.class, PlaceOrderCommand.Line.class, Buyer.class, NormalizedOrderRequest.class)) {
            assertThat(componentNames(type))
                    .as(type.getSimpleName())
                    .noneMatch(name -> FORBIDDEN.contains(name.toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    void buyerFieldsAreExactlyNamePhoneAddressZone() {
        assertThat(componentNames(Buyer.class)).containsExactly("name", "phone", "address", "zone");
    }

    @Test
    void toStringNeverLeaksPersonalData() {
        Buyer buyer = new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);
        PlaceOrderCommand command = new PlaceOrderCommand(
                "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", buyer, List.of(new PlaceOrderCommand.Line(1L, 2)));
        assertThat(buyer.toString()).doesNotContain("Maria", "5550000", "Falsa");
        assertThat(command.toString()).doesNotContain("Maria", "5550000", "Falsa");
    }
}
