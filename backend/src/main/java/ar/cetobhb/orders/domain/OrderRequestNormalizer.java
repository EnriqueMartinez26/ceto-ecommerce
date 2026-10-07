package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and normalizes a raw checkout request. Failures are {@link ValidationException}s that carry the
 * reason code only, never the input (PII).
 *
 * <p>Validation order (the first failure wins, so the outcome is deterministic):
 * <ol>
 *   <li>idempotency key: {@code MISSING_IDEMPOTENCY_KEY}</li>
 *   <li>buyer name: {@code BLANK_BUYER_NAME}, {@code BUYER_NAME_TOO_LONG}</li>
 *   <li>phone: {@code INVALID_PHONE}</li>
 *   <li>address: {@code BLANK_ADDRESS}, {@code ADDRESS_TOO_LONG}</li>
 *   <li>zone: {@code INVALID_ZONE}</li>
 *   <li>lines: {@code EMPTY_CART}, {@code TOO_MANY_LINES}, then per line in request order
 *       {@code QUANTITY_BELOW_MIN} / {@code QUANTITY_ABOVE_MAX}, finally {@code DUPLICATE_PRODUCT_LINE}</li>
 * </ol>
 *
 * <p>Text is normalized to Unicode NFC, trimmed, and every whitespace run becomes one space. Maximum lengths
 * (in characters) match the V3 columns: name 120, address 500. The key must be a UUID in its 36-character form.
 */
public class OrderRequestNormalizer {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_ADDRESS_LENGTH = 500;

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    private final PhoneNumberNormalizer phoneNormalizer;
    private final OrderLimits limits;

    public OrderRequestNormalizer(PhoneNumberNormalizer phoneNormalizer, OrderLimits limits) {
        this.phoneNormalizer = phoneNormalizer;
        this.limits = limits;
    }

    public NormalizedOrderRequest normalize(PlaceOrderCommand command) {
        UUID key = parseKey(command.idempotencyKey());
        Buyer buyer = command.buyer();
        if (buyer == null) {
            throw new ValidationException(ReasonCode.BLANK_BUYER_NAME);
        }
        String name = text(buyer.name(), MAX_NAME_LENGTH, ReasonCode.BLANK_BUYER_NAME, ReasonCode.BUYER_NAME_TOO_LONG);
        String phone = phoneNormalizer.normalize(buyer.phone());
        String address =
                text(buyer.address(), MAX_ADDRESS_LENGTH, ReasonCode.BLANK_ADDRESS, ReasonCode.ADDRESS_TOO_LONG);
        if (buyer.zone() == null) {
            throw new ValidationException(ReasonCode.INVALID_ZONE);
        }
        return new NormalizedOrderRequest(key, name, phone, address, buyer.zone(), validLines(command.lines()));
    }

    private static UUID parseKey(String raw) {
        String key = raw == null ? "" : raw.strip();
        if (!UUID_TEXT.matcher(key).matches()) {
            throw new ValidationException(ReasonCode.MISSING_IDEMPOTENCY_KEY);
        }
        return UUID.fromString(key);
    }

    private static String text(String raw, int maxLength, ReasonCode blank, ReasonCode tooLong) {
        String value = raw == null
                ? ""
                : WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ").strip();
        if (value.isEmpty()) {
            throw new ValidationException(blank);
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new ValidationException(tooLong);
        }
        return value;
    }

    private List<PlaceOrderCommand.Line> validLines(List<PlaceOrderCommand.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(ReasonCode.EMPTY_CART);
        }
        if (lines.size() > limits.maxLinesPerOrder()) {
            throw new ValidationException(ReasonCode.TOO_MANY_LINES);
        }
        Set<Long> seen = new HashSet<>();
        boolean duplicated = false;
        for (PlaceOrderCommand.Line line : lines) {
            if (line.quantity() < 1) {
                throw new ValidationException(ReasonCode.QUANTITY_BELOW_MIN);
            }
            if (line.quantity() > limits.maxUnitsPerLine()) {
                throw new ValidationException(ReasonCode.QUANTITY_ABOVE_MAX);
            }
            duplicated |= !seen.add(line.productId());
        }
        if (duplicated) {
            throw new ValidationException(ReasonCode.DUPLICATE_PRODUCT_LINE);
        }
        return lines.stream().sorted(Comparator.comparingLong(PlaceOrderCommand.Line::productId)).toList();
    }
}
