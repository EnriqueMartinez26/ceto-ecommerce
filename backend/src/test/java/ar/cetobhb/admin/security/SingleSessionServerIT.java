package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.ContractAssertions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

/**
 * One administrator session at a time (REQ-AUTH-04): a newer login ends the older one, and the older one is told so
 * with the normal 401 problem body (not Security's plain-text default). The registry that enforces it is kept honest by
 * the container's session events: a session that is logged out or destroyed leaves it.
 */
class SingleSessionServerIT extends AbstractAdminServerIT {

    @Autowired
    private SessionRegistry sessionRegistry;

    @Test
    void aSecondLoginMakesTheFirstSessionA401AndTheSecondStaysValid() throws Exception {
        AdminClient first = loggedInClient();
        assertThat(first.isAuthenticated()).isTrue();

        AdminClient second = loggedInClient();

        AdminClient.Reply replay = first.get(AdminClient.PROBE_PATH);
        assertThat(replay.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(replay.body(), replay.header("x-request-id"),
                "admin-error-unauthenticated.json", AdminClient.PROBE_PATH);
        assertThat(replay.header("content-type")).startsWith("application/problem+json");
        assertThat(second.isAuthenticated()).isTrue();
    }

    @Test
    void theSupersededSessionStaysDeadForEveryCallAndForAMutation() throws Exception {
        AdminClient first = loggedInClient();
        AdminClient second = loggedInClient();

        assertThat(first.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
        assertThat(first.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
        first.bootstrap();
        assertThat(first.postWithToken(AdminClient.PROBE_PATH).status()).isEqualTo(401);
        assertThat(second.isAuthenticated()).isTrue();
    }

    @Test
    void theSameBrowserLoggingInAgainKeepsAWorkingSessionWithANewId() throws Exception {
        AdminClient client = loggedInClient();
        String before = client.cookie(AdminClient.SESSION_COOKIE);

        client.bootstrap();
        AdminClient.Reply login = client.login(ar.cetobhb.support.AdminTestCredentials.username(),
                ar.cetobhb.support.AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        assertThat(client.cookie(AdminClient.SESSION_COOKIE)).isNotEqualTo(before);
        assertThat(client.isAuthenticated()).isTrue();
    }

    @Test
    void whenTheNewerSessionLogsOutTheOlderOneStaysDead() throws Exception {
        AdminClient first = loggedInClient();
        AdminClient second = loggedInClient();
        second.bootstrap();

        assertThat(second.logout().status()).isEqualTo(204);

        assertThat(first.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
        assertThat(second.isAuthenticated()).isFalse();
    }

    @Test
    void theRegistryForgetsASessionThatIsLoggedOut() throws Exception {
        AdminClient client = loggedInClient();
        String id = client.cookie(AdminClient.SESSION_COOKIE);
        assertThat(registered(id)).as("the registry knows the live session").isTrue();
        client.bootstrap();

        client.logout();

        assertThat(registered(id)).as("the registry forgot the logged-out session").isFalse();
    }

    @Test
    void theRegistryHoldsAtMostOneLiveSessionAfterManyLogins() throws Exception {
        loggedInClient();
        loggedInClient();
        AdminClient last = loggedInClient();

        long live = sessionRegistry.getAllPrincipals().stream()
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
                .filter(information -> !information.isExpired()).count();

        assertThat(live).isEqualTo(1);
        assertThat(registered(last.cookie(AdminClient.SESSION_COOKIE))).isTrue();
    }

    private boolean registered(String sessionId) {
        List<SessionInformation> all = sessionRegistry.getAllPrincipals().stream()
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, true).stream()).toList();
        return all.stream().anyMatch(information -> information.getSessionId().equals(sessionId));
    }
}
