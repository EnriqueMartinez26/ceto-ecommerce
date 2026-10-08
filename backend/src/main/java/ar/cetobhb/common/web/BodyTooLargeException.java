package ar.cetobhb.common.web;

import java.io.IOException;

/**
 * Thrown by {@link CountingInputStream} when a request body is longer than the configured limit. It is an
 * {@link IOException} so every reader (Jackson, servlet code) treats it as a failed read; the error advice finds it in
 * the cause chain and answers 413. The message holds the limit only, never any body content.
 */
public final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public BodyTooLargeException(long maxBytes) {
        super("Request body exceeds " + maxBytes + " bytes");
    }
}
