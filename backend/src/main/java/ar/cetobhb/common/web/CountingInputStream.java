package ar.cetobhb.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;

/**
 * Request body stream that hands over at most {@code maxBytes} and fails with {@link BodyTooLargeException} when the
 * body has even one more byte. Reads are capped at the remaining allowance, so the caller never receives (or buffers)
 * more than the limit; the byte after the limit is only probed. The failure is sticky.
 */
final class CountingInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long count;
    private boolean exceeded;

    CountingInputStream(ServletInputStream delegate, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        failIfExceeded();
        long remaining = maxBytes - count;
        if (remaining == 0) {
            // At the limit: the body is fine only if nothing follows.
            int probe = delegate.read(buffer, offset, 1);
            if (probe == -1) {
                return -1;
            }
            exceeded = true;
            throw new BodyTooLargeException(maxBytes);
        }
        int n = delegate.read(buffer, offset, (int) Math.min(length, remaining));
        if (n > 0) {
            count += n;
        }
        return n;
    }

    private void failIfExceeded() throws BodyTooLargeException {
        if (exceeded) {
            throw new BodyTooLargeException(maxBytes);
        }
    }

    @Override
    public boolean isFinished() {
        return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
        delegate.setReadListener(listener);
    }
}
