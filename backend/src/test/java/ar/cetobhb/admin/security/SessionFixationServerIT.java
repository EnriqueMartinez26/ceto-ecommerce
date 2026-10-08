package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import org.junit.jupiter.api.Test;

/**
 * Session fixation (REQ-AUTH-07). An anonymous admin request opens no server session, so the test makes one on the
 * server side exactly as an earlier page of the same application could have, hands its id to the "victim" browser, and
 * logs that browser in: the id must change, and the id the attacker knows must be worthless.
 */
class SessionFixationServerIT extends AbstractAdminServerIT {

    @Test
    void theSessionIdPresentedBeforeLoginIsReplacedAndTheOldIdIsNotAuthenticated() throws Exception {
        String known = createServerSession();
        assertThat(findServerSession(known)).as("the pre-login session exists on the server").isNotNull();
        AdminClient victim = newClient();
        victim.bootstrap();
        victim.setCookie(AdminClient.SESSION_COOKIE, known);

        AdminClient.Reply login = victim.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        String issued = victim.cookie(AdminClient.SESSION_COOKIE);
        assertThat(issued).as("a new id is issued").isNotNull().isNotEqualTo(known);
        assertThat(login.setCookie("JSESSIONID")).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        assertThat(victim.isAuthenticated()).as("the victim is logged in").isTrue();

        AdminClient attacker = newClient();
        attacker.setCookie(AdminClient.SESSION_COOKIE, known);
        assertThat(attacker.isAuthenticated()).as("the id the attacker knew is not authenticated").isFalse();
        assertThat(findServerSession(known)).as("the old id no longer names a session").isNull();
    }

    @Test
    void aFabricatedIdThatTheServerNeverIssuedNeverBecomesTheSessionOfTheLogin() throws Exception {
        AdminClient client = newClient();
        client.bootstrap();
        client.setCookie(AdminClient.SESSION_COOKIE, "FABRICATED0123456789ABCDEF012345");

        AdminClient.Reply login = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(login.status()).isEqualTo(204);
        assertThat(client.cookie(AdminClient.SESSION_COOKIE)).isNotEqualTo("FABRICATED0123456789ABCDEF012345");
        AdminClient attacker = newClient();
        attacker.setCookie(AdminClient.SESSION_COOKIE, "FABRICATED0123456789ABCDEF012345");
        assertThat(attacker.isAuthenticated()).isFalse();
    }
}
