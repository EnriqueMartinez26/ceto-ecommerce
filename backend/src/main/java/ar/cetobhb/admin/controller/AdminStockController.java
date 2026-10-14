package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.service.AdminInventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration stock routes (AINV-01 to AINV-07). HTTP only: bounds and the mapping to the facade; the lock, the
 * rules and the audit row are decided below. Authentication and CSRF are the Security chain's job (every route under
 * {@code /api/admin/} is protected by the filter scope), and the response is never cached by it. There is no absolute
 * "set the stock" route: the adjustment is always relative.
 */
@RestController
@RequestMapping(path = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminStockController {

    /** 2^53 - 1: the largest integer a JavaScript client reads exactly. */
    private static final long MAX_ID = 9_007_199_254_740_991L;

    private final AdminInventoryService inventory;

    AdminStockController(AdminInventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/stock")
    AdminStockResponse stock() {
        return AdminStockResponse.from(inventory.stockView());
    }

    @PostMapping(path = "/stock/{productId}/adjust", consumes = MediaType.APPLICATION_JSON_VALUE)
    AdjustedStockResponse adjust(@PathVariable @Min(1) @Max(MAX_ID) long productId,
            @Valid @RequestBody AdjustStockRequest request) {
        return AdjustedStockResponse.from(inventory.adjust(productId, request.delta(), request.reason()));
    }

    @GetMapping("/inventory/reconciliation")
    AdminReconciliationResponse reconciliation() {
        return AdminReconciliationResponse.from(inventory.reconciliation());
    }
}
