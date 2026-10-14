package ar.cetobhb.orders.domain;

import ar.cetobhb.common.domain.PhoneNumberNormalizer;
import ar.cetobhb.common.domain.ValidationException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the admin search box asks for, as EXACT lookup values (AOR-02, AD-A17). Never a pattern: the repository
 * compares both candidates with {@code =}, so no LIKE input is ever produced and nothing matches by prefix.
 *
 * <p>Interpretation of the (trimmed) text:
 * <ul>
 *   <li>{@code CETO-} plus six or more digits, in the canonical format of {@link OrderNumber} and in any letter case
 *       (the text is uppercased with {@link Locale#ROOT} first): that order number;</li>
 *   <li>one to nine digits: the order number with that sequence value AND, when the digits also form a valid
 *       mobile number, that phone (both are exact candidates);</li>
 *   <li>anything else: the E.164 phone given by {@link PhoneNumberNormalizer}, or no key when it rejects it.</li>
 * </ul>
 * Text longer than {@link #MAX_QUERY_LENGTH} is discarded before any parsing. A caller turns "no key" into an empty
 * page, never an error. The query text itself is never logged or echoed.
 *
 * @param number canonical order number to match, or null
 * @param phone  E.164 phone to match, or null; at least one of the two is set
 */
public record OrderSearchKey(String number, String phone) {

    public static final int MAX_QUERY_LENGTH = 64;

    private static final int MAX_NUMBER_DIGITS = 9;

    private static final Pattern ORDER_NUMBER = Pattern.compile("CETO-([0-9]{6,})");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1," + MAX_NUMBER_DIGITS + "}");

    public OrderSearchKey {
        if (number == null && phone == null) {
            throw new IllegalArgumentException("a search key needs a number or a phone");
        }
    }

    /** The phone is a buyer phone: it is never printed. */
    @Override
    public String toString() {
        return "OrderSearchKey[number=" + number + ", phone=" + (phone == null ? "none" : "hidden") + "]";
    }

    public static Optional<OrderSearchKey> parse(String q, PhoneNumberNormalizer normalizer) {
        if (q == null || q.length() > MAX_QUERY_LENGTH) {
            return Optional.empty();
        }
        String text = q.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String upperText = text.toUpperCase(Locale.ROOT);
        var orderNumber = ORDER_NUMBER.matcher(upperText);
        if (orderNumber.matches()) {
            String number = canonicalNumber(orderNumber.group(1));
            return number != null && number.equals(upperText)
                    ? Optional.of(new OrderSearchKey(number, null))
                    : Optional.empty();
        }
        if (DIGITS.matcher(text).matches()) {
            String number = canonicalNumber(text);
            String phone = phoneOrNull(text, normalizer);
            return number == null && phone == null
                    ? Optional.empty()
                    : Optional.of(new OrderSearchKey(number, phone));
        }
        String phone = phoneOrNull(text, normalizer);
        return phone == null ? Optional.empty() : Optional.of(new OrderSearchKey(null, phone));
    }

    /** The order number of this sequence value, or null when the digits are not a positive long. */
    private static String canonicalNumber(String digits) {
        try {
            long value = Long.parseLong(digits);
            return value < 1 ? null : OrderNumber.format(value);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private static String phoneOrNull(String text, PhoneNumberNormalizer normalizer) {
        try {
            return normalizer.normalize(text);
        } catch (ValidationException invalid) {
            return null;
        }
    }
}
