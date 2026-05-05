package com.nubbank.baas.ncube.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Caches the request body bytes once at construction and replays them on every
 * {@link #getInputStream()} / {@link #getReader()} call. Allows a filter to inspect
 * the body (e.g. for HMAC validation) without consuming it for the controller.
 *
 * <p>Spring's {@link org.springframework.web.util.ContentCachingRequestWrapper} is
 * NOT suitable for this purpose — it caches bytes for {@code getContentAsByteArray()}
 * but does not replay them via {@code getInputStream()}.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private static final class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        CachedBodyServletInputStream(byte[] cachedBody) {
            this.input = new ByteArrayInputStream(cachedBody);
        }

        @Override public int read() { return input.read(); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { /* not used */ }
    }
}
