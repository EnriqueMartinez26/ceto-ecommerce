package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderRequestNormalizerTest {

    private static final String KEY = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
    private static final String PHONE = "+54 9 351 555 0000";
    private static final Buyer BUYER = new Buyer("Maria Perez", PHONE, "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);

    private final OrderRequestNormalizer normalizer =
            new OrderRequestNormalizer(new PhoneNumberNormalizer(), new OrderLimits(10, 20));

    private static PlaceOrderCommand command(Buyer buyer, PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand(KEY, buyer, List.of(lines));
    }

    private static PlaceOrderCommand.Line line(long id, int qty) {
        return new PlaceOrderCommand.Line(id, qty);
    }

    private static List<PlaceOrderCommand.Line> lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> line(i, 1)).toList();
    }

    private void assertRejected(PlaceOrderCommand command, ReasonCode reason) {
        assertThatThrownBy(() -> normalizer.normalize(command))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void normalizesAValidRequest() {
        NormalizedOrderRequest request = normalizer.normalize(command(BUYER, line(7, 2), line(3, 1)));
        assertThat(request.idempotencyKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(request.name()).isEqualTo("Maria Perez");
        assertThat(request.phoneE164()).isEqualTo("+5493515550000");
        assertThat(request.address()).isEqualTo("Calle Falsa 123");
        assertThat(request.zone()).isEqualTo(ShippingZone.CORDOBA_CAPITAL);
        assertThat(request.lines()).containsExactly(line(3, 1), line(7, 2));
    }

    @ParameterizedTest(name = "quantity {0} -> {1}")
    @CsvSource({"0, QUANTITY_BELOW_MIN", "-1, QUANTITY_BELOW_MIN", "11, QUANTITY_ABOVE_MAX", "1000, QUANTITY_ABOVE_MAX"})
    void rejectsQuantitiesOutsideTheRange(int quantity, ReasonCode reason) {
        assertRejected(command(BUYER, line(1, quantity)), reason);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsTheQuantityBounds(int quantity) {
        assertThat(normalizer.normalize(command(BUYER, line(1, quantity))).lines()).containsExactly(line(1, quantity));
    }

    @Test
    void theMaximumQuantityComesFromTheInjectedLimit() {
        OrderRequestNormalizer strict = new OrderRequestNormalizer(new PhoneNumberNormalizer(), new OrderLimits(3, 20));
        assertThatThrownBy(() -> strict.normalize(command(BUYER, line(1, 4))))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.QUANTITY_ABOVE_MAX));
        assertThat(strict.normalize(command(BUYER, line(1, 3))).lines()).hasSize(1);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new OrderLimits(0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderLimits(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEmptyOrMissingCart() {
        assertRejected(new PlaceOrderCommand(KEY, BUYER, List.of()), ReasonCode.EMPTY_CART);
        assertRejected(new PlaceOrderCommand(KEY, BUYER, null), ReasonCode.EMPTY_CART);
    }

    @Test
    void rejectsADuplicatedProduct() {
        assertRejected(command(BUYER, line(5, 1), line(6, 1), line(5, 2)), ReasonCode.DUPLICATE_PRODUCT_LINE);
    }

    @Test
    void acceptsTwentyLinesAndRejectsTwentyOne() {
        assertThat(normalizer.normalize(new PlaceOrderCommand(KEY, BUYER, lines(20))).lines()).hasSize(20);
        assertRejected(new PlaceOrderCommand(KEY, BUYER, lines(21)), ReasonCode.TOO_MANY_LINES);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", " "})
    void rejectsABlankName(String name) {
        assertRejected(command(new Buyer(name, PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.BLANK_BUYER_NAME);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsABlankAddress(String address) {
        assertRejected(command(new Buyer("Maria", PHONE, address, ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.BLANK_ADDRESS);
    }

    @Test
    void anAmbiguousArgentineTenDigitPhoneIsNormalizedAsAMobile() {
        // D-20261007-11: "11 5555 1234" used to be rejected; it now becomes the mobile +549...
        Buyer buyer = new Buyer("Maria", "11 5555 1234", "Calle 1", ShippingZone.CORDOBA_CAPITAL);
        assertThat(normalizer.normalize(command(buyer, line(1, 1))).phoneE164()).isEqualTo("+5491155551234");
    }

    @Test
    void rejectsAMissingZone() {
        assertRejected(command(new Buyer("Maria", PHONE, "Calle 1", null), line(1, 1)), ReasonCode.INVALID_ZONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "+1 650 253 0000", "+54 9 351 555 0000 ext 5"})
    void rejectsAnInvalidPhone(String phone) {
        assertRejected(command(new Buyer("Maria", phone, "Calle 1", ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.INVALID_PHONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "not-a-uuid", "1-1-1-1-1", "3f2b8c1e5d4a4e6f9a7b1c2d3e4f5a6b",
            "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b-extra", "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6g"})
    void rejectsAMissingOrMalformedIdempotencyKey(String key) {
        assertRejected(new PlaceOrderCommand(key, BUYER, List.of(line(1, 1))), ReasonCode.MISSING_IDEMPOTENCY_KEY);
    }

    @Test
    void theKeyIsTrimmedAndCaseInsensitive() {
        PlaceOrderCommand command =
                new PlaceOrderCommand(" 3F2B8C1E-5D4A-4E6F-9A7B-1C2D3E4F5A6B ", BUYER, List.of(line(1, 1)));
        assertThat(normalizer.normalize(command).idempotencyKey()).isEqualTo(UUID.fromString(KEY));
    }

    @Test
    void trimsCollapsesWhitespaceAndAppliesUnicodeNfc() {
        String decomposedName = "  José\t  Perez \n";
        Buyer buyer = new Buyer(decomposedName, PHONE, "  Calle   Falsa  123  ", ShippingZone.RESTO_DEL_PAIS);
        NormalizedOrderRequest request = normalizer.normalize(command(buyer, line(1, 1)));
        assertThat(request.name()).isEqualTo("José Perez");
        assertThat(request.address()).isEqualTo("Calle Falsa 123");
    }

    @Test
    void enforcesTheDatabaseColumnLengths() {
        Buyer longName = new Buyer("a".repeat(121), PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL);
        Buyer longAddress = new Buyer("Maria", PHONE, "a".repeat(501), ShippingZone.CORDOBA_CAPITAL);
        assertRejected(command(longName, line(1, 1)), ReasonCode.BUYER_NAME_TOO_LONG);
        assertRejected(command(longAddress, line(1, 1)), ReasonCode.ADDRESS_TOO_LONG);
        Buyer atLimit = new Buyer("a".repeat(120), PHONE, "b".repeat(500), ShippingZone.CORDOBA_CAPITAL);
        assertThat(normalizer.normalize(command(atLimit, line(1, 1))).address()).hasSize(500);
    }

    @Test
    void rejectsANullLineWithoutThrowingANullPointer() {
        assertRejected(new PlaceOrderCommand(KEY, BUYER, Arrays.asList(null, line(1, 1))), ReasonCode.INVALID_LINE);
        assertRejected(new PlaceOrderCommand(KEY, BUYER, Arrays.asList(line(1, 1), null)), ReasonCode.INVALID_LINE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsANonPositiveProductId(long productId) {
        assertRejected(command(BUYER, line(productId, 1)), ReasonCode.INVALID_LINE);
    }

    @Test
    void anInvalidLineIsReportedBeforeItsQuantityAndBeforeDuplicates() {
        assertRejected(command(BUYER, line(0, 0)), ReasonCode.INVALID_LINE);
        assertRejected(command(BUYER, line(5, 1), line(5, 1), line(-1, 1)), ReasonCode.INVALID_LINE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mar\u0000ia", "\u0000", "Mar\u0007ia", "Mar\u001Fia", "Mar\u007Fia", "Maria\u0000\u0085",
        "Mar‮ia", "‪", "Mar⁦ia", "⁩", "Mar​ia", "Mar‌ia", "Mar‍ia", "﻿Maria",
        "Mar\uD800ia", "Maria\uDC00", "Mar\uDC00\uD800ia"})
    void rejectsControlFormatAndUnpairedSurrogateCharactersInTheName(String name) {
        assertRejected(command(new Buyer(name, PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.INVALID_CHARACTERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Calle\u0000 1", "Calle 1‮", "Calle​1", "﻿Calle 1", "Calle \uD800 1",
        "Calle\u0001"})
    void rejectsControlFormatAndUnpairedSurrogateCharactersInTheAddress(String address) {
        assertRejected(command(new Buyer("Maria", PHONE, address, ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.INVALID_CHARACTERS);
    }

    @Test
    void whitespaceControlsCollapseButNulIsRejectedAndPairedSurrogatesAreKept() {
        // Tab, newline, CR, NEL and NBSP are whitespace: they collapse to one space (see the class Javadoc).
        Buyer buyer = new Buyer("Ana\t\r\n\u0085 Maria 😀", PHONE, "Calle\u000B1", ShippingZone.CORDOBA_CAPITAL);
        NormalizedOrderRequest request = normalizer.normalize(command(buyer, line(1, 1)));
        assertThat(request.name()).isEqualTo("Ana Maria 😀");
        assertThat(request.address()).isEqualTo("Calle 1");
    }

    @Test
    void invalidCharactersAreReportedBeforeTheBlankAndLengthChecksOfTheSameField() {
        assertRejected(command(new Buyer("\u0000", PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL), line(1, 1)),
                ReasonCode.INVALID_CHARACTERS);
        assertRejected(command(new Buyer("a".repeat(121) + "‮", PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL),
                line(1, 1)), ReasonCode.INVALID_CHARACTERS);
        // The name is checked before the phone, the phone before the address.
        assertRejected(command(new Buyer("M\u0000", "bad", "C\u0000", null), line(1, 1)), ReasonCode.INVALID_CHARACTERS);
        assertRejected(command(new Buyer("M", "bad", "C\u0000", null), line(1, 1)), ReasonCode.INVALID_PHONE);
    }

    @Test
    void validationOrderIsKeyThenBuyerThenLines() {
        Buyer broken = new Buyer(" ", "bad", " ", null);
        assertRejected(new PlaceOrderCommand(null, broken, List.of()), ReasonCode.MISSING_IDEMPOTENCY_KEY);
        assertRejected(new PlaceOrderCommand(KEY, broken, List.of()), ReasonCode.BLANK_BUYER_NAME);
        assertRejected(new PlaceOrderCommand(KEY, new Buyer("M", "bad", " ", null), List.of()),
                ReasonCode.INVALID_PHONE);
        assertRejected(new PlaceOrderCommand(KEY, new Buyer("M", PHONE, " ", null), List.of()),
                ReasonCode.BLANK_ADDRESS);
        assertRejected(new PlaceOrderCommand(KEY, new Buyer("M", PHONE, "C", null), List.of()),
                ReasonCode.INVALID_ZONE);
        assertRejected(new PlaceOrderCommand(KEY, BUYER, List.of()), ReasonCode.EMPTY_CART);
    }

    @Test
    void failuresNeverEchoPersonalData() {
        Buyer buyer = new Buyer("Maria Secreta", "bad-phone-5550000", "Calle Secreta 9", ShippingZone.CORDOBA_CAPITAL);
        assertThatThrownBy(() -> normalizer.normalize(command(buyer, line(1, 1))))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Secreta", "5550000"));
        Buyer longName = new Buyer("Secreta".repeat(30), PHONE, "Calle 1", ShippingZone.CORDOBA_CAPITAL);
        assertThatThrownBy(() -> normalizer.normalize(command(longName, line(1, 1))))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Secreta"));
    }
}
