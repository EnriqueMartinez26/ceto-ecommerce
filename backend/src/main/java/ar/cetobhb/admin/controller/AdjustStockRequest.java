package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.service.AdminInventoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The body of a stock adjustment (AINV-02): a relative change and a reason from the closed set. There is no note
 * (D-20261008-23): a {@code note} property is an unknown property and the strict mapper answers 400 MALFORMED_REQUEST,
 * so no free text can reach the audit log. A zero delta passes the bounds and is refused by the service
 * ({@code INVALID_STOCK_DELTA}).
 */
record AdjustStockRequest(
        @NotNull @Min(-AdminInventoryService.MAX_DELTA) @Max(AdminInventoryService.MAX_DELTA) Integer delta,
        @NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer expectedOnHand,
        @NotNull StockAdjustmentReason reason) {
}
