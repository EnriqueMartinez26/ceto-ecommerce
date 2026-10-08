package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;

/** The counting stream lets exactly {@code max} bytes through and fails on the first byte after them. */
class CountingInputStreamTest {

    private static CountingInputStream streamOf(int size, long max) {
        return new CountingInputStream(new DelegatingServletInputStream(new ByteArrayInputStream(new byte[size])), max);
    }

    @Test
    void readsABodyOfExactlyTheLimit() throws IOException {
        assertThat(streamOf(10, 10).readAllBytes()).hasSize(10);
    }

    @Test
    void failsOnTheFirstByteOverTheLimitWithTheSingleByteRead() throws IOException {
        CountingInputStream in = streamOf(11, 10);
        for (int i = 0; i < 10; i++) {
            assertThat(in.read()).isZero();
        }
        assertThatThrownBy(in::read).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void aBulkReadNeverReturnsMoreThanTheLimitAndTheNextOneFails() throws IOException {
        CountingInputStream in = streamOf(100, 10);
        assertThat(in.read(new byte[64], 0, 64)).isEqualTo(10);
        assertThatThrownBy(() -> in.read(new byte[64], 0, 64)).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void neverHandsOverMoreThanTheLimitBeforeFailing() throws IOException {
        CountingInputStream in = streamOf(100, 10);
        byte[] buffer = new byte[64];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buffer, 0, buffer.length)) != -1) {
                total += n;
            }
        } catch (BodyTooLargeException expected) {
            assertThat(total).as("bytes returned to the caller before the failure").isLessThanOrEqualTo(10);
            return;
        }
        throw new AssertionError("expected BodyTooLargeException");
    }

    @Test
    void keepsFailingAfterTheLimitWasCrossedEvenIfOnlyOneByteWasOver() throws IOException {
        CountingInputStream in = streamOf(11, 10);
        assertThat(in.readNBytes(10)).hasSize(10);
        assertThatThrownBy(in::read).isInstanceOf(BodyTooLargeException.class);
        assertThatThrownBy(in::read).isInstanceOf(BodyTooLargeException.class);
        assertThatThrownBy(() -> in.read(new byte[4], 0, 4)).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void aZeroLengthReadIsNotCountedAsAProbe() throws IOException {
        CountingInputStream in = streamOf(10, 10);
        assertThat(in.readNBytes(10)).hasSize(10);
        assertThat(in.read(new byte[4], 0, 0)).isZero();
    }

    @Test
    void anEmptyBodyIsAnOrdinaryEndOfStream() throws IOException {
        CountingInputStream in = streamOf(0, 10);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read(new byte[4], 0, 4)).isEqualTo(-1);
    }

    @Test
    void theExceptionIsAnIoExceptionWithoutRequestData() {
        assertThat(new BodyTooLargeException(10)).isInstanceOf(IOException.class).hasMessage("Request body exceeds 10 bytes");
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> streamOf(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
