package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.ContractAssertions;
import org.apache.catalina.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;

/**
 * Idle expiry (REQ-AUTH-09, DEV-6). The configured timeout is 480 minutes by default; a test cannot wait that long and
 * the setting cannot go below 5 minutes, so the live session of a real login is shortened through the embedded Tomcat
 * session manager and the next call is the 401 of a session that ended.
 */
class IdleExpiryServerIT extends AbstractAdminServerIT {

    @Autowired
    private SessionRegistry sessionRegistry;

    @Test
    void aFreshLoginSessionHasTheConfiguredIdleTimeoutOfEightHours() throws Exception {
        AdminClient client = loggedInClient();

        Session session = findServerSession(client.cookie(AdminClient.SESSION_COOKIE));

        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).as("seconds").isEqualTo(28_800);
    }

    @Test
    void aSessionThatIsIdleLongerThanItsTimeoutIsA401AndIsGoneFromTheServer() throws Exception {
        AdminClient client = loggedInClient();
        String id = client.cookie(AdminClient.SESSION_COOKIE);
        assertThat(client.isAuthenticated()).isTrue();
        findServerSession(id).setMaxInactiveInterval(1);

        Thread.sleep(1_600);
        AdminClient.Reply reply = client.get(AdminClient.PROBE_PATH);

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.header("x-request-id"),
                "admin-error-unauthenticated.json", AdminClient.PROBE_PATH);
        assertThat(findServerSession(id)).as("the container dropped the expired session").isNull();
    }

    @Test
    void anExpiredSessionLeavesTheRegistryAndCannotBeReusedByTheSameCookie() throws Exception {
        AdminClient client = loggedInClient();
        String id = client.cookie(AdminClient.SESSION_COOKIE);
        findServerSession(id).setMaxInactiveInterval(1);

        Thread.sleep(1_600);
        client.get(AdminClient.PROBE_PATH);

        boolean registered = sessionRegistry.getAllPrincipals().stream()
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, true).stream())
                .anyMatch(information -> information.getSessionId().equals(id));
        assertThat(registered).as("the registry forgot the expired session").isFalse();
        assertThat(client.get(AdminClient.PROBE_PATH).status()).isEqualTo(401);
    }

    @Test
    void aSessionThatIsUsedWithinItsTimeoutStaysValid() throws Exception {
        AdminClient client = loggedInClient();
        findServerSession(client.cookie(AdminClient.SESSION_COOKIE)).setMaxInactiveInterval(4);

        for (int i = 0; i < 3; i++) {
            Thread.sleep(1_000);
            assertThat(client.isAuthenticated()).as("call %d", i).isTrue();
        }
    }
}
