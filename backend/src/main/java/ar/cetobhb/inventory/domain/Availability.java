package ar.cetobhb.inventory.domain;

import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;

/**
 * Derived availability (CLAUDE.md section 4, rule 2). The stock lives only on the base product; a
 * combo of N base units has availability floor((on_hand - reserved) / N). Never negative.
 */
public final class Availability {

    private Availability() {
    }

    public static long forBase(long onHand, long reserved) {
        return Math.max(0L, onHand - reserved);
    }

    public static long forCombo(long onHand, long reserved, int baseUnitsPerUnit) {
        if (baseUnitsPerUnit <= 0) {
            throw new ValidationException(ReasonCode.INVALID_COMBO_LINK);
        }
        return forBase(onHand, reserved) / baseUnitsPerUnit;
    }
}
