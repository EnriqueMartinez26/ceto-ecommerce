package ar.cetobhb.common.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.regex.Pattern;

/**
 * Normalizes a buyer phone to E.164 (D-20261007-01). Only a valid number that ends up typed MOBILE is accepted
 * (D-20261007-06, adjusted by D-20261007-11). Numbers without a country code are read as Argentine (region AR).
 * An Argentine 10-digit number typed FIXED_LINE or FIXED_LINE_OR_MOBILE (written without 15 or 9, so ambiguous) is
 * read as a mobile: {@code +54 9} plus the national number is re-parsed and must be MOBILE. Numbers with an
 * extension are rejected. Foreign numbers are accepted only when they are MOBILE in international form. The
 * failure carries only the reason code, never the input (PII). Before parsing, only digits, spaces and the
 * separators {@code + - ( ) .} are accepted (after folding Unicode spaces, dashes and bidi marks to ASCII);
 * anything else (letters would turn into vanity numbers such as {@code CASA}) is rejected.
 */
public class PhoneNumberNormalizer {

    static final String DEFAULT_REGION = "AR";

    private static final int AR_NATIONAL_LENGTH = 10;

    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[0-9 +\\-().]+");

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        String ascii = foldLookalikes(raw);
        if (!ALLOWED_CHARACTERS.matcher(ascii).matches()) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        PhoneNumber number = parse(ascii);
        if (number.hasExtension() || !util.isValidNumber(number)) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        PhoneNumberType type = util.getNumberType(number);
        if (type != PhoneNumberType.MOBILE && isAmbiguousArgentineNumber(number, type)) {
            number = parse("+" + number.getCountryCode() + "9" + util.getNationalSignificantNumber(number));
            type = util.isValidNumber(number) ? util.getNumberType(number) : null;
        }
        if (type != PhoneNumberType.MOBILE) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        return util.format(number, PhoneNumberFormat.E164);
    }

    /**
     * Maps what chat apps and word processors paste into a phone field to plain ASCII: Unicode space separators
     * and tab become a space, the dash family (U+2010 to U+2015, U+2212) becomes a hyphen, and bidirectional
     * marks are dropped. Anything else is left untouched for the charset check to reject.
     */
    private static String foldLookalikes(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (isBidiMark(cp)) {
                return;
            }
            if (cp == '	' || Character.getType(cp) == Character.SPACE_SEPARATOR) {
                out.append(' ');
            } else if (isDash(cp)) {
                out.append('-');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static boolean isBidiMark(int cp) {
        return cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }

    private static boolean isDash(int cp) {
        return (cp >= 0x2010 && cp <= 0x2015) || cp == 0x2212;
    }

    private PhoneNumber parse(String text) {
        try {
            return util.parse(text, DEFAULT_REGION);
        } catch (NumberParseException e) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
    }

    private boolean isAmbiguousArgentineNumber(PhoneNumber number, PhoneNumberType type) {
        return DEFAULT_REGION.equals(util.getRegionCodeForNumber(number))
                && (type == PhoneNumberType.FIXED_LINE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE)
                && util.getNationalSignificantNumber(number).length() == AR_NATIONAL_LENGTH;
    }
}
