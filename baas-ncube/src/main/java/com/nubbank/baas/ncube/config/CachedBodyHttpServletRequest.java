package com.nubbank.baas.ncube.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *
 * <p>Caps body size at {@value #MAX_BODY_BYTES} bytes to prevent OOM on large or
 * malicious payloads. Internal-service endpoints (BVN/NIN verification, NIP transfer,
 * account queries) carry small JSON payloads; this limit is well above the practical
 * maximum but bounded.
 *
 * <p>Synchronous-only — the inner {@code ServletInputStream}'s async-dispatch hooks
 * ({@code isReady}, {@code setReadListener}) are stubs. Do not use with async servlets.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 1 MB cap on the request body — generous for internal-service JSON payloads. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            throw new IOException(
                "Request body too large: declared " + declaredLength
                + " bytes (max " + MAX_BODY_BYTES + ")");
        }
        // Read with a hard cap even when Content-Length is unknown (-1) or under-reports
        this.cachedBody = readBoundedBytes(request.getInputStream(), MAX_BODY_BYTES);
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

    /**
     * Reads up to {@code maxBytes} from {@code in}; throws {@link IOException} if the
     * stream produces more. Different from {@code copyToByteArray} which is unbounded.
     */
    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Request body exceeded " + maxBytes + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static final class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        CachedBodyServletInputStream(byte[] cachedBody) {
            this.input = new ByteArrayInputStream(cachedBody);
        }

        @Override public int read() { return input.read(); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { /* sync-only */ }
    }
}
