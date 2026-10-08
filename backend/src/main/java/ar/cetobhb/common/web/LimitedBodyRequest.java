package ar.cetobhb.common.web;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Request whose body can be read only up to a limit, whatever the client declared (chunked bodies declare nothing and
 * {@code Content-Length} can lie). Both {@link #getInputStream()} and {@link #getReader()} go through the same
 * {@link CountingInputStream}. The servlet contract is kept: one of the two may be used per request (the other throws
 * {@link IllegalStateException}) and repeated calls return the same object. An unusable character encoding is an
 * {@link UnsupportedEncodingException}, as in the container, never an unchecked exception.
 */
final class LimitedBodyRequest extends HttpServletRequestWrapper {

    private final long maxBytes;
    private CountingInputStream limited;
    private BufferedReader reader;

    LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (reader != null) {
            throw new IllegalStateException("getReader() has already been called for this request");
        }
        return stream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            if (limited != null) {
                throw new IllegalStateException("getInputStream() has already been called for this request");
            }
            reader = new BufferedReader(new InputStreamReader(stream(), charset()));
        }
        return reader;
    }

    private CountingInputStream stream() throws IOException {
        if (limited == null) {
            limited = new CountingInputStream(super.getInputStream(), maxBytes);
        }
        return limited;
    }

    private Charset charset() throws UnsupportedEncodingException {
        String encoding = getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.ISO_8859_1;
        }
        try {
            return Charset.forName(encoding);
        } catch (UnsupportedCharsetException | IllegalCharsetNameException unusable) {
            UnsupportedEncodingException failure = new UnsupportedEncodingException(encoding);
            failure.initCause(unusable);
            throw failure;
        }
    }
}
