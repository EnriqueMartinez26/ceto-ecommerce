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
 *   <li>buyer name: {@code INVALID_CHARACTERS}, {@code BLANK_BUYER_NAME}, {@code BUYER_NAME_TOO_LONG}</li>
 *   <li>phone: {@code INVALID_PHONE}</li>
 *   <li>address: {@code INVALID_CHARACTERS}, {@code BLANK_ADDRESS}, {@code ADDRESS_TOO_LONG}</li>
 *   <li>zone: {@code INVALID_ZONE}</li>
 *   <li>lines: {@code EMPTY_CART}, {@code TOO_MANY_LINES}, then per line in request order
 *       {@code INVALID_LINE} (null line or product id &lt;= 0), {@code QUANTITY_BELOW_MIN} /
 *       {@code QUANTITY_ABOVE_MAX}, finally {@code DUPLICATE_PRODUCT_LINE}</li>
 * </ol>
 *
 * <p>Text is normalized to Unicode NFC, trimmed, and every whitespace run becomes one space (tab, newline, CR,
 * NEL, NBSP and every other Unicode White_Space character collapse; they are not rejected). After that, any
 * remaining control character (Unicode Cc, such as NUL), format character (Cf, such as bidi controls
 * U+202A-U+202E and U+2066-U+2069, zero-width U+200B-U+200D, U+FEFF) or unpaired surrogate is rejected with
 * {@code INVALID_CHARACTERS}. That check runs on a field before its blank and length checks. Maximum lengths
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
        if (value.codePoints().anyMatch(OrderRequestNormalizer::isForbiddenCharacter)) {
            throw new ValidationException(ReasonCode.INVALID_CHARACTERS);
        }
        if (value.isEmpty()) {
            throw new ValidationException(blank);
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new ValidationException(tooLong);
        }
        return value;
    }

    /** Control (Cc), format (Cf) and unpaired surrogate code points; whitespace is already collapsed by then. */
    private static boolean isForbiddenCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE;
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
            if (line == null || line.productId() <= 0) {
                throw new ValidationException(ReasonCode.INVALID_LINE);
            }
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
