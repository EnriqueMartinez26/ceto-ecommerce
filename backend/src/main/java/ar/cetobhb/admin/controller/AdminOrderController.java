package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.service.OrderListQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration order views (AOR-01, AOR-02, AOR-03). HTTP only: bounds and the mapping to the facade; the
 * search, the paging and the countdown are decided below. Authentication is the Security chain's job (every route
 * under {@code /api/admin/} is protected by the filter scope), and the response is never cached by it.
 *
 * <p>The search text is a request value and is never logged: a rejected one is the neutral 400, an unreadable one an
 * empty page.
 */
@RestController
@RequestMapping(path = "/api/admin/orders", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminOrderController {

    /** 2^53 - 1: the largest integer a JavaScript client reads exactly. */
    private static final long MAX_ID = OrderListQuery.MAX_ID;
    private static final String ALL = "all";

    private final AdminOrderService orders;

    AdminOrderController(AdminOrderService orders) {
        this.orders = orders;
    }

    /** {@code status} is a state name or {@code all}; {@code after} is the {@code nextAfter} of the previous page. */
    @GetMapping
    AdminOrderListResponse list(
            @RequestParam(defaultValue = ALL) @Size(max = 16)
            @Pattern(regexp = "all|PAGO_PENDIENTE|PAGADO|DESPACHADO|ENTREGADO|CANCELADO") String status,
            @RequestParam(required = false) @Size(max = 64) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(OrderListQuery.MAX_SIZE) int size,
            @RequestParam(required = false) @Min(1) @Max(MAX_ID) Long after) {
        OrderStatus filter = ALL.equals(status) ? null : OrderStatus.valueOf(status);
        return AdminOrderListResponse.from(orders.list(new OrderListQuery(filter, q, size, after)));
    }

    @GetMapping("/{id}")
    AdminOrderDetailResponse detail(@PathVariable @Min(1) @Max(MAX_ID) long id) {
        return AdminOrderDetailResponse.from(orders.detail(id));
    }
}
