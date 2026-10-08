package ar.cetobhb.common.web;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Request whose body can be read only up to a limit, whatever the client declared (chunked bodies declare nothing and
 * {@code Content-Length} can lie). Both {@link #getInputStream()} and {@link #getReader()} go through the same
 * {@link CountingInputStream}; the stream is created once, as the servlet contract allows one of the two per request.
 */
final class LimitedBodyRequest extends HttpServletRequestWrapper {

    private final long maxBytes;
    private CountingInputStream limited;

    LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (limited == null) {
            limited = new CountingInputStream(super.getInputStream(), maxBytes);
        }
        return limited;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
