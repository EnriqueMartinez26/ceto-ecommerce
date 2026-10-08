package ar.cetobhb.orders;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.orders.domain.NewOrder;
import ar.cetobhb.orders.domain.NewOrderLine;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.repository.Order;
import ar.cetobhb.orders.repository.OrderRepository;
import ar.cetobhb.orders.repository.OrderSummaryRow;
import ar.cetobhb.support.AbstractPostgresIT;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AOR-01 / AOR-02 / AOR-08 at the repository: keyset pages, exact keys and the sentinel parameters (AD-A18). */
class OrderPageRepositoryIT extends AbstractPostgresIT {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");
    private static final String PHONE_A = "+5493515550199";
    private static final String PHONE_B = "+5493515550200";

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long productId;
    private int sequence;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        productId = jdbc.queryForObject("INSERT INTO products (name, price) VALUES ('p', 10.00) RETURNING id",
                Long.class);
        sequence = 0;
    }

    private long place(String phone) {
        sequence++;
        NewOrder data = new NewOrder("CETO-%06d".formatted(sequence), UUID.randomUUID(), "c".repeat(64),
                "Buyer " + sequence, phone, "Street " + sequence, ShippingZone.CORDOBA_CAPITAL,
                T0.plusSeconds(172_800),
                List.of(new NewOrderLine(productId, "p", false, new BigDecimal("10.00"), 1, productId, 1)));
        return tx.execute(status -> orders.saveAndFlush(Order.place(data, T0.plusSeconds(sequence))).id());
    }

    private long placePaid(String phone) {
        long id = place(phone);
        tx.executeWithoutResult(status -> orders.findById(id).orElseThrow().markPaid(T0.plusSeconds(1000)));
        return id;
    }

    private long placeCancelled(String phone) {
        long id = place(phone);
        tx.executeWithoutResult(status -> orders.findById(id).orElseThrow()
                .cancel(ar.cetobhb.orders.domain.CancelReason.CANCELLED_BY_ADMIN, T0.plusSeconds(1000)));
        return id;
    }

    private List<OrderSummaryRow> page(long before, OrderStatus status, String number, String phone, int size) {
        boolean anyStatus = status == null;
        boolean anyKey = number == null && phone == null;
        return orders.findPage(before, anyStatus, anyStatus ? OrderStatus.PAGO_PENDIENTE : status, anyKey,
                number == null ? "" : number, phone == null ? "" : phone, PageRequest.ofSize(size));
    }

    private List<OrderSummaryRow> all() {
        return page(Long.MAX_VALUE, null, null, null, 500);
    }

    @Test
    void keysetPagesOf50CoverAll120OrdersWithoutGapsOrDuplicates() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            created.add(place(PHONE_A));
        }
        List<Long> seen = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        long after = Long.MAX_VALUE;
        while (true) {
            List<OrderSummaryRow> rows = page(after, null, null, null, 51);
            boolean more = rows.size() > 50;
            List<OrderSummaryRow> pageRows = more ? rows.subList(0, 50) : rows;
            sizes.add(pageRows.size());
            pageRows.forEach(r -> seen.add(r.id()));
            if (!more) {
                break;
            }
            after = pageRows.get(49).id();
        }

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(seen).hasSize(120).doesNotHaveDuplicates();
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(new HashSet<>(seen)).isEqualTo(new HashSet<>(created));
    }

    @Test
    void theBeforeBoundIsStrict() {
        long first = place(PHONE_A);
        long second = place(PHONE_A);

        assertThat(page(second, null, null, null, 10)).extracting(OrderSummaryRow::id).containsExactly(first);
        assertThat(page(first, null, null, null, 10)).isEmpty();
    }

    @Test
    void theStatusFilterKeepsOnlyThatStatus() {
        long pending = place(PHONE_A);
        long paid = placePaid(PHONE_A);
        long cancelled = placeCancelled(PHONE_A);

        assertThat(page(Long.MAX_VALUE, OrderStatus.PAGADO, null, null, 10)).extracting(OrderSummaryRow::id)
                .containsExactly(paid);
        assertThat(page(Long.MAX_VALUE, OrderStatus.PAGO_PENDIENTE, null, null, 10))
                .extracting(OrderSummaryRow::id).containsExactly(pending);
        assertThat(page(Long.MAX_VALUE, OrderStatus.CANCELADO, null, null, 10)).extracting(OrderSummaryRow::id)
                .containsExactly(cancelled);
        assertThat(page(Long.MAX_VALUE, OrderStatus.ENTREGADO, null, null, 10)).isEmpty();
    }

    @Test
    void absentFiltersReturnEveryOrderOfEveryStatus() {
        long pending = place(PHONE_A);
        long paid = placePaid(PHONE_B);
        long cancelled = placeCancelled(PHONE_A);

        assertThat(all()).extracting(OrderSummaryRow::id).containsExactly(cancelled, paid, pending);
    }

    @Test
    void anOrderNumberMatchesExactlyAndNeverByPrefix() {
        for (int i = 0; i < 13; i++) {
            place(PHONE_A);
        }

        assertThat(page(Long.MAX_VALUE, null, "CETO-000001", null, 10)).extracting(OrderSummaryRow::orderNumber)
                .containsExactly("CETO-000001");
        assertThat(page(Long.MAX_VALUE, null, "CETO-000012", null, 10)).extracting(OrderSummaryRow::orderNumber)
                .containsExactly("CETO-000012");
        assertThat(page(Long.MAX_VALUE, null, "CETO-0000", null, 10)).isEmpty();
        assertThat(page(Long.MAX_VALUE, null, "CETO-%", null, 10)).isEmpty();
    }

    @Test
    void aPhoneMatchesExactlyAndNeverByPrefixOrPattern() {
        long mine = place(PHONE_A);
        place(PHONE_B);

        assertThat(page(Long.MAX_VALUE, null, null, PHONE_A, 10)).extracting(OrderSummaryRow::id)
                .containsExactly(mine);
        assertThat(page(Long.MAX_VALUE, null, null, "+549351555019", 10)).isEmpty();
        assertThat(page(Long.MAX_VALUE, null, null, "+5493515550%", 10)).isEmpty();
    }

    @Test
    void numberAndPhoneCandidatesAreAlternatives() {
        long byNumber = place(PHONE_A);
        place(PHONE_B);
        long byPhone = place(PHONE_B);

        assertThat(page(Long.MAX_VALUE, null, "CETO-000001", PHONE_B, 10)).extracting(OrderSummaryRow::id)
                .containsExactly(byPhone, byNumber + 1, byNumber);
    }

    @Test
    void statusAndKeyCompose() {
        long paid = placePaid(PHONE_A);
        placeCancelled(PHONE_A);
        placePaid(PHONE_B);

        assertThat(page(Long.MAX_VALUE, OrderStatus.PAGADO, null, PHONE_A, 10)).extracting(OrderSummaryRow::id)
                .containsExactly(paid);
        assertThat(page(Long.MAX_VALUE, OrderStatus.PAGO_PENDIENTE, null, PHONE_A, 10)).isEmpty();
    }

    @Test
    void rowsCarryTheListFieldsAsStored() {
        long id = place(PHONE_A);

        OrderSummaryRow row = all().get(0);

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.orderNumber()).isEqualTo("CETO-000001");
        assertThat(row.status()).isEqualTo(OrderStatus.PAGO_PENDIENTE);
        assertThat(row.buyerName()).isEqualTo("Buyer 1");
        assertThat(row.buyerPhoneE164()).isEqualTo(PHONE_A);
        assertThat(row.total()).isEqualByComparingTo("10.00");
        assertThat(row.createdAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(row.reservedUntil()).isEqualTo(T0.plusSeconds(172_800));
    }

    @Test
    void theRowHasNoAddressComponentAndToStringHidesPersonalData() {
        Set<String> names = new HashSet<>();
        for (RecordComponent component : OrderSummaryRow.class.getRecordComponents()) {
            names.add(component.getName().toLowerCase());
        }
        assertThat(names).noneMatch(n -> n.contains("address") || n.contains("shipping"));
        assertThat(Arrays.stream(OrderSummaryRow.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("id", "orderNumber", "status", "buyerName", "buyerPhoneE164", "total", "createdAt",
                        "reservedUntil");

        place(PHONE_A);
        String text = all().get(0).toString();
        assertThat(text).doesNotContain("Buyer 1").doesNotContain(PHONE_A);
    }
}
