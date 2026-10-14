package ar.cetobhb.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.ContractAssertions;
import org.junit.jupiter.api.Test;

/**
 * The session scenarios of X4 re-asserted on a REAL route (AUTH-04, AUTH-08, ERR-06): a cookie that logout or a newer
 * login killed gets the 401 golden from {@code GET /api/admin/orders}, not from the unmapped probe path; a live session
 * gets the page. Real server, because MockMvc's {@code user()} replaces the session machinery.
 */
class AdminOrdersSessionServerIT extends AbstractAdminServerIT {

    private static final String LIST = "/api/admin/orders";

    @Test
    void aLiveSessionReadsTheListWithTheNoStoreHeader() throws Exception {
        AdminClient client = loggedInClient();

        AdminClient.Reply reply = client.get(LIST);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header("content-type")).startsWith("application/json");
        assertThat(reply.header("cache-control")).contains("no-store");
        assertThat(reply.body()).isEqualTo("{\"orders\":[],\"nextAfter\":null}");
    }

    @Test
    void theOldCookieAfterLogoutIsA401OnTheRealRoute() throws Exception {
        AdminClient client = loggedInClient();
        String oldSession = client.cookie(AdminClient.SESSION_COOKIE);
        assertThat(client.get(LIST).status()).isEqualTo(200);
        client.bootstrap();
        assertThat(client.logout().status()).isEqualTo(204);

        client.setCookie(AdminClient.SESSION_COOKIE, oldSession);
        AdminClient.Reply replay = client.get(LIST);

        assertThat(replay.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(replay.body(), replay.header("x-request-id"),
                "admin-error-unauthenticated.json", LIST);
    }

    @Test
    void aNewerLoginMakesTheOlderSessionA401OnTheRealRouteAndTheDetail() throws Exception {
        AdminClient first = loggedInClient();
        assertThat(first.get(LIST).status()).isEqualTo(200);

        AdminClient second = loggedInClient();

        AdminClient.Reply listReplay = first.get(LIST);
        assertThat(listReplay.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(listReplay.body(), listReplay.header("x-request-id"),
                "admin-error-unauthenticated.json", LIST);
        assertThat(first.get(LIST + "/1").status()).isEqualTo(401);
        assertThat(second.get(LIST).status()).isEqualTo(200);
        assertThat(second.get(LIST + "/1").status()).as("authenticated, unknown order").isEqualTo(404);
    }

    @Test
    void anAnonymousBrowserAndAForgedCookieGetThe401OnTheRealRoute() throws Exception {
        AdminClient anonymous = newClient();
        AdminClient.Reply denied = anonymous.get(LIST);
        assertThat(denied.status()).isEqualTo(401);
        assertThat(denied.setCookie(AdminClient.SESSION_COOKIE)).as("an anonymous request opens no session").isNull();
        assertThat(anonymous.get(LIST + "/1").setCookie(AdminClient.SESSION_COOKIE)).isNull();

        AdminClient forged = newClient();
        forged.setCookie(AdminClient.SESSION_COOKIE, "0123456789ABCDEF0123456789ABCDEF");
        assertThat(forged.get(LIST).status()).isEqualTo(401);
        assertThat(forged.get(LIST + "/1").status()).isEqualTo(401);
    }
}
