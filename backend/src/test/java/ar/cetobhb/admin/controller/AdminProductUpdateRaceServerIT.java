package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.BurstRunner.Outcome;
import ar.cetobhb.support.BurstRunner.Report;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrent edits from the same read, over real HTTP (ACAT-04, "Lost update"): six admins send an edit of the same
 * version at once. Exactly one is applied (200) and every other is a 409 {@code CONFLICT}: at least one conflict must
 * appear, and there is NO 503 (D-20261008-49): the test pool (30) is not smaller than the threads, so a 503 would be a
 * lock wait or a deadlock hiding in a retry. The stored price is the winner's, the version moved by exactly one and a
 * single {@code PRICE_CHANGED} row exists. Nothing is silently overwritten and nothing is a 5xx.
 */
class AdminProductUpdateRaceServerIT extends AbstractAdminServerIT {

    private static final int EDITORS = 6;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    private record Result(BigDecimal price, int status, String body) {
    }

    @RepeatedTest(3)
    void sixEditsOfTheSameVersionApplyExactlyOneAndTheOthersAreConflicts() throws Exception {
        long id = catalog.createBase(product("Frasco", "100.00")).id();
        AdminClient client = loggedInClient();
        List<Callable<Result>> tasks = new ArrayList<>();
        for (int i = 1; i <= EDITORS; i++) {
            BigDecimal price = new BigDecimal("100.00").add(new BigDecimal(i));
            tasks.add(() -> {
                AdminClient.Reply reply = client.send("PUT", "/api/admin/products/" + id, "application/json",
                        "{\"name\":\"Frasco\",\"price\":\"" + price.toPlainString() + "\",\"version\":0}",
                        client.csrfHeader());
                return new Result(price, reply.status(), reply.body());
            });
        }

        Report<Result> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isGreaterThanOrEqualTo(EDITORS / 2);
        assertThat(report.outcomes()).allMatch(Outcome::succeeded);
        List<Result> results = report.outcomes().stream().map(Outcome::value).toList();
        List<Result> winners = results.stream().filter(r -> r.status() == 200).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).extracting(Result::status).as("no 503: a lock wait or a deadlock must not hide in a retry")
                .doesNotContain(503);
        List<Result> conflicts = results.stream().filter(r -> r.status() != 200).toList();
        assertThat(conflicts).as("at least one 409 CONFLICT").isNotEmpty();
        assertThat(conflicts).allMatch(r -> r.status() == 409 && r.body().contains("\"code\":\"CONFLICT\""));
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo(winners.get(0).price());
        assertThat(jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = 'PRICE_CHANGED'",
                Long.class)).isEqualTo(1L);
    }
}
