package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.CountingPasswordEncoder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gaps of the login limiter on the real server (review fix batch 8): clients of one IPv6 /64 share a bucket (the key
 * is the first 64 bits, REQ-ABU-02), and a chunked body spends a token like any other login POST, whatever it
 * carries (the body-size filter only refuses a DECLARED oversize body before the limiter).
 */
class AdminLoginLimiterGapsServerIT extends AbstractAdminServerIT {

    private static final String LOGIN = "/api/admin/login";
    private static final String WRONG = "not-the-password-of-the-owner";

    @Autowired
    private AdminProperties admin;

    private int burst() {
        return admin.loginRateLimit().burst();
    }

    private AdminClient bootstrapped(String address) throws Exception {
        AdminClient client = newClient(address);
        client.bootstrap();
        return client;
    }

    @Test
    void sixClientsOfOneIpv6SlashSixtyFourShareOneBucketAndTheSixthGets429() throws Exception {
        for (int i = 1; i <= burst(); i++) {
            AdminClient inside = bootstrapped("2001:db8:aaaa:1::" + Integer.toHexString(i));
            assertThat(inside.login(AdminTestCredentials.username(), WRONG).status())
                    .as("attempt %d from a different interface id of the same /64", i).isEqualTo(401);
        }
        int hashesBefore = CountingPasswordEncoder.matches();

        AdminClient sameSubnet = bootstrapped("2001:db8:aaaa:1:ffff:ffff:ffff:ffff");
        AdminClient.Reply limited = sameSubnet.login(AdminTestCredentials.username(),
                AdminTestCredentials.password());

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("retry-after")).matches("[1-9][0-9]*");
        assertThat(CountingPasswordEncoder.matches()).as("no hash for the limited /64").isEqualTo(hashesBefore);
    }

    @Test
    void anotherIpv6SlashSixtyFourIsNotAffected() throws Exception {
        for (int i = 1; i <= burst(); i++) {
            assertThat(bootstrapped("2001:db8:bbbb:1::" + Integer.toHexString(i))
                    .login(AdminTestCredentials.username(), WRONG).status()).isEqualTo(401);
        }
        assertThat(bootstrapped("2001:db8:bbbb:1::99").login(AdminTestCredentials.username(), WRONG).status())
                .isEqualTo(429);

        AdminClient owner = bootstrapped("2001:db8:bbbb:2::1");

        assertThat(owner.login(AdminTestCredentials.username(), AdminTestCredentials.password()).status())
                .isEqualTo(204);
    }

    @Test
    void anIpv6ClientAndAnIpv4ClientNeverShareABucket() throws Exception {
        for (int i = 1; i <= burst(); i++) {
            assertThat(bootstrapped("2001:db8:cccc:1::" + Integer.toHexString(i))
                    .login(AdminTestCredentials.username(), WRONG).status()).isEqualTo(401);
        }

        assertThat(bootstrapped("203.0.115.1").login(AdminTestCredentials.username(), WRONG).status())
                .isEqualTo(401);
    }

    @Test
    void chunkedLoginsOfASmallBodySpendATokenEach() throws Exception {
        AdminClient client = bootstrapped("203.0.115.10");
        for (int i = 0; i < burst(); i++) {
            assertThat(statusOf(chunkedLogin(client, 20, true))).as("chunked attempt %d", i + 1).isEqualTo(401);
        }

        assertThat(statusOf(chunkedLogin(client, 20, true))).isEqualTo(429);
        assertThat(client.login(AdminTestCredentials.username(), WRONG).status()).isEqualTo(429);
    }

    @Test
    void chunkedHundredKilobyteLoginsRefusedLaterStillSpentTheirToken() throws Exception {
        AdminClient client = bootstrapped("203.0.115.11");
        int hashesBefore = CountingPasswordEncoder.matches();
        for (int i = 0; i < burst(); i++) {
            int status = statusOf(chunkedLogin(client, 100_000, true));
            assertThat(status).as("chunked oversize attempt %d", i + 1).isBetween(400, 499);
        }

        AdminClient.Reply next = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(next.status()).as("the five refused chunked bodies used up the burst").isEqualTo(429);
        assertThat(CountingPasswordEncoder.matches()).isEqualTo(hashesBefore);
    }

    private static int statusOf(String reply) {
        String statusLine = reply.lines().findFirst().orElse("");
        assertThat(statusLine).as(statusLine).matches("HTTP/1\\.1 \\d{3}.*");
        return Integer.parseInt(statusLine.substring(9, 12));
    }

    /** A chunked login POST written on a raw socket, from the client's own forwarded address, with its XSRF token. */
    private String chunkedLogin(AdminClient client, int passwordLength, boolean withToken) throws IOException {
        byte[] form = ("username=" + AdminTestCredentials.username() + "&password=" + "x".repeat(passwordLength))
                .getBytes(StandardCharsets.US_ASCII);
        StringBuilder request = new StringBuilder("POST " + LOGIN + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\nX-Forwarded-For: " + client.forwardedFor()
                + "\r\nCookie: __Host-XSRF-TOKEN=" + client.xsrf() + "\r\n");
        if (withToken) {
            request.append("X-XSRF-TOKEN: ").append(client.xsrf()).append("\r\n");
        }
        request.append("\r\n");
        for (int offset = 0; offset < form.length; offset += 4096) {
            int length = Math.min(4096, form.length - offset);
            request.append(Integer.toHexString(length)).append("\r\n")
                    .append(new String(form, offset, length, StandardCharsets.US_ASCII)).append("\r\n");
        }
        request.append("0\r\n\r\n");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(10_000);
            try {
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException serverAlreadyAnswered) {
                // The server may close once it has answered; the reply is read below either way.
            }
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
