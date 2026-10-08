package ar.cetobhb.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The mapper judges nothing: gaps in the wire request become nulls for the normalizer to report. */
class CheckoutMapperTest {

    private static final String KEY = "8c2f4d0e-2f43-4e6b-8d5e-0a1b2c3d4e5f";
    private static final BuyerRequest BUYER =
            new BuyerRequest("Maria Perez", "+5493515550000", "Calle Falsa 123", "RESTO_DEL_PAIS");

    @Test
    void aCompleteRequestMapsEveryField() {
        PlaceOrderCommand command = CheckoutMapper.toCommand(KEY,
                new PlaceOrderRequest(BUYER, List.of(new LineRequest(7L, 3), new LineRequest(9L, 1))));

        assertThat(command.idempotencyKey()).isEqualTo(KEY);
        assertThat(command.buyer()).isEqualTo(
                new Buyer("Maria Perez", "+5493515550000", "Calle Falsa 123", ShippingZone.RESTO_DEL_PAIS));
        assertThat(command.lines()).containsExactly(
                new PlaceOrderCommand.Line(7L, 3), new PlaceOrderCommand.Line(9L, 1));
    }

    @Test
    void aMissingBuyerMapsToANullBuyer() {
        PlaceOrderCommand command = CheckoutMapper.toCommand(KEY,
                new PlaceOrderRequest(null, List.of(new LineRequest(7L, 3))));

        assertThat(command.buyer()).isNull();
        assertThat(command.lines()).containsExactly(new PlaceOrderCommand.Line(7L, 3));
    }

    @Test
    void aMissingLinesListMapsToNullLines() {
        PlaceOrderCommand command = CheckoutMapper.toCommand(KEY, new PlaceOrderRequest(BUYER, null));

        assertThat(command.lines()).isNull();
    }

    @Test
    void aNullLineAndALineMissingItsIdOrQuantityMapToNullEntries() {
        List<LineRequest> lines = Arrays.asList(
                null, new LineRequest(null, 2), new LineRequest(7L, null), new LineRequest(7L, 2));

        PlaceOrderCommand command = CheckoutMapper.toCommand(KEY, new PlaceOrderRequest(BUYER, lines));

        assertThat(command.lines()).containsExactly(null, null, null, new PlaceOrderCommand.Line(7L, 2));
    }

    @Test
    void anUnknownMissingOrDifferentlyCasedZoneMapsToANullZone() {
        for (String zone : new String[] {"NOWHERE", "resto_del_pais", "", null}) {
            BuyerRequest buyer = new BuyerRequest("Maria Perez", "+5493515550000", "Calle Falsa 123", zone);

            PlaceOrderCommand command = CheckoutMapper.toCommand(KEY,
                    new PlaceOrderRequest(buyer, List.of(new LineRequest(7L, 1))));

            assertThat(command.buyer().zone()).as("zone '%s'", zone).isNull();
        }
    }
}
