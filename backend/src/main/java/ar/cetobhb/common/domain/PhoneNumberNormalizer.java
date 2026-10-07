package ar.cetobhb.common.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Normalizes a buyer phone to E.164 (D-20261007-01). Only a valid number whose type is exactly MOBILE is accepted
 * (D-20261007-06): FIXED_LINE and the ambiguous FIXED_LINE_OR_MOBILE are rejected. Numbers without a country code
 * are read as Argentine (region AR). The failure carries only the reason code, never the input (PII).
 */
public class PhoneNumberNormalizer {

    static final String DEFAULT_REGION = "AR";

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        PhoneNumber number;
        try {
            number = util.parse(raw, DEFAULT_REGION);
        } catch (NumberParseException e) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        if (!util.isValidNumber(number) || util.getNumberType(number) != PhoneNumberType.MOBILE) {
            throw new ValidationException(ReasonCode.INVALID_PHONE);
        }
        return util.format(number, PhoneNumberFormat.E164);
    }
}
