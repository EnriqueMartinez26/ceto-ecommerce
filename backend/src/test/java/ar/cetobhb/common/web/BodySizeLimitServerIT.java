package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Body limit on a real socket (REQ-ABU body limit): chunked bodies have no declared length, so only the counting
 * stream can stop them, and a lying {@code Content-Length} must not make the server wait for the announced bytes. The
 * request is written by hand because the JDK client would not send a lying length or a chunked body this way.
 */
class BodySizeLimitServerIT extends AbstractServerIT {

    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);
    private static final int CHUNK = 4096;

    @Autowired
    private JdbcTemplate jdbc;

    private record RawReply(int status, String headers, String body) {
    }

    private static String freshClient() {
        return "198.19." + (NEXT_CLIENT.get() >> 8 & 0xff) + "." + (NEXT_CLIENT.getAndIncrement() & 0xff);
    }

    private String head(String framing) {
        return "POST /api/checkout/orders HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/json\r\nAccept: application/json\r\n"
                + "Idempotency-Key: " + UUID.randomUUID() + "\r\nX-Forwarded-For: " + freshClient() + "\r\n"
                + framing + "\r\nConnection: close\r\n\r\n";
    }

    private RawReply exchange(String head, byte[][] writes) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            try {
                for (byte[] write : writes) {
                    out.write(write);
                }
                out.flush();
            } catch (IOException serverAlreadyAnswered) {
                // Acceptable: the server may close early once it answered; the reply is read below either way.
            }
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            while (!headBytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                headBytes.write(b);
            }
            String headers = headBytes.toString(StandardCharsets.ISO_8859_1);
            // The server may keep the connection open while it waits for a body that was announced but never sent:
            // read exactly Content-Length bytes when it is known, otherwise until the server closes.
            java.util.regex.Matcher length =
                    java.util.regex.Pattern.compile("(?im)^content-length:\\s*(\\d+)").matcher(headers);
            byte[] body = length.find() ? in.readNBytes(Integer.parseInt(length.group(1))) : in.readAllBytes();
            return new RawReply(Integer.parseInt(headers.substring(9, 12)), headers,
                    new String(body, StandardCharsets.UTF_8));
        }
    }

    /** The payload cut into {@value #CHUNK}-byte chunks (hex size 1000), plus the terminating chunk. */
    private static byte[][] chunked(byte[] payload) {
        int chunks = payload.length / CHUNK;
        byte[][] writes = new byte[chunks + 1][];
        for (int i = 0; i < chunks; i++) {
            byte[] part = java.util.Arrays.copyOfRange(payload, i * CHUNK, (i + 1) * CHUNK);
            writes[i] = ("1000\r\n" + new String(part, StandardCharsets.US_ASCII) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }
        writes[chunks] = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        return writes;
    }

    private long orders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    @Test
    void aChunkedBodyOf100KbWithoutContentLengthIs413AndCreatesNoOrder() throws IOException {
        long before = orders();
        // A well-formed, never-ending list of order lines: the parser keeps reading until the stream stops it.
        String line = "{\"productId\":1,\"quantity\":1},";
        String endless = ("{\"lines\":[" + line.repeat(100 * 1024 / line.length() + 1)).substring(0, 100 * 1024);
        byte[][] writes = chunked(endless.getBytes(StandardCharsets.US_ASCII));

        RawReply reply = exchange(head("Transfer-Encoding: chunked"), writes);

        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.headers()).containsIgnoringCase("application/problem+json").containsIgnoringCase("no-store");
        assertThat(reply.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"").contains("\"requestId\":\"");
        assertThat(orders()).isEqualTo(before);
    }

    @Test
    void aSmallChunkedBodyPassesTheFilterAndIsJudgedByTheParser() throws IOException {
        byte[] body = "2\r\n{}\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        RawReply reply = exchange(head("Transfer-Encoding: chunked"), new byte[][] {body});

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("VALIDATION_ERROR");
    }

    @Test
    void aContentLengthThatLiesAboutAHugeBodyIs413WithoutWaitingForTheBytes() throws IOException {
        // Announces 1 MB, sends 10 bytes and then nothing: the answer must come from the header alone.
        RawReply reply = exchange(head("Content-Length: 1048576"), new byte[][] {"0123456789".getBytes(StandardCharsets.US_ASCII)});

        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aContentLengthLiarThatSendsMoreThanItAnnouncedNeverMakesTheServerFail() throws IOException {
        // Announces 12 bytes but sends 30000: the container reads only the announced bytes.
        byte[] payload = ("{\"x\":\"" + "a".repeat(30_000) + "\"}").getBytes(StandardCharsets.US_ASCII);

        RawReply reply = exchange(head("Content-Length: 12"), new byte[][] {payload});

        assertThat(reply.status()).isLessThan(500);
    }
}
