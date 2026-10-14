package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.inventory.domain.ReservationMismatch;
import java.util.List;

/**
 * The reservation reconciliation (AINV-06): only the base products whose {@code reserved} counter disagrees with their
 * pending order lines, by id. {@code reserved} is null when the product has pending demand but no stock row.
 * {@code truncated} is true when the cap cut the list short.
 */
record AdminReconciliationResponse(List<Mismatch> mismatches, boolean truncated) {

    record Mismatch(long productId, Integer reserved, long expected) {

        static Mismatch from(ReservationMismatch mismatch) {
            return new Mismatch(mismatch.productId(), mismatch.reserved(), mismatch.expected());
        }
    }

    static AdminReconciliationResponse from(CappedList<ReservationMismatch> result) {
        return new AdminReconciliationResponse(result.rows().stream().map(Mismatch::from).toList(),
                result.truncated());
    }
}
