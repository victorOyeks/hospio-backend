package com.hospio.common.instrumentation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Logs request bodies and error-response bodies so a failing call can be understood from the log
 * alone. On with {@code hospio.logging.bodies=true} (env {@code HOSPIO_LOG_BODIES}); meant for dev and
 * staging. Any field whose name looks secret (pass, pin, token, otp, code, secret, card, pan, cvv, authorization,
 * id number; any case, anywhere in the name) is masked, string or number; bodies are cut at 2 KB; actuator, swagger,
 * file uploads and webhooks (POS, payment and messaging providers send card and personal data) are never logged.
 *
 * The response is not buffered: it streams to the client as usual and at most its first 2 KB are copied aside, and
 * only while the status is an error, which is all that is ever logged.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "hospio.logging", name = "bodies", havingValue = "true")
public class BodyLoggingFilter extends OncePerRequestFilter {

    private static final int MAX = 2048;
    /** A JSON key containing a secret-looking fragment, then its string or number value. */
    private static final Pattern SECRETS = Pattern.compile(
            "(\"[^\"]*(?:pass|pin|token|otp|code|secret|card|pan|cvv|authorization|idnumber|id_number)[^\"]*\"\\s*:\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.eE+-]*)",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String type = request.getContentType();
        return uri.contains("/actuator") || uri.contains("/swagger") || uri.contains("/v3/api-docs") || uri.contains("/webjars")
                || uri.contains("/webhooks/")
                || (type != null && type.startsWith("multipart/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX);
        ErrorBodyCapture res = new ErrorBodyCapture(response);
        try {
            chain.doFilter(req, res);
        } finally {
            res.flushWriter();
            String reqBody = body(req.getContentAsByteArray(), req.getCharacterEncoding(), req.getContentLengthLong());
            int status = res.getStatus();
            String resBody = status >= 400 ? body(res.captured(), res.getCharacterEncoding(), res.total()) : "";
            if (!reqBody.isEmpty() && !"GET".equals(request.getMethod())) {
                log.info("    body {} {}: {}", request.getMethod(), request.getRequestURI(), mask(reqBody));
            }
            if (!resBody.isEmpty()) {
                log.warn("    response {} {} -> {}: {}", request.getMethod(), request.getRequestURI(), status, mask(resBody));
            }
        }
    }

    private static String body(byte[] bytes, String encoding, long total) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int len = Math.min(bytes.length, MAX);
        String text = new String(bytes, 0, len, encoding == null ? StandardCharsets.UTF_8 : charset(encoding));
        String oneLine = text.replaceAll("\\s*\\n\\s*", " ").trim();
        long size = Math.max(total, bytes.length);
        return size > MAX ? oneLine + " …(" + size + " bytes)" : oneLine;
    }

    private static Charset charset(String encoding) {
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static String mask(String json) {
        return SECRETS.matcher(json).replaceAll("$1\"***\"");
    }

    /**
     * Passes the body straight through to the client and keeps a copy of at most {@link #MAX} bytes of what is written
     * while the status is 400 or more (the status is set before the body is written).
     */
    static final class ErrorBodyCapture extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
        private long total;
        private ServletOutputStream stream;
        private PrintWriter writer;

        ErrorBodyCapture(HttpServletResponse response) {
            super(response);
        }

        byte[] captured() {
            return copy.toByteArray();
        }

        long total() {
            return total;
        }

        private void keep(byte[] b, int off, int len) {
            if (getStatus() < 400) {
                return;
            }
            total += len;
            int room = MAX - copy.size();
            if (room > 0) {
                copy.write(b, off, Math.min(room, len));
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream target = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        target.write(b);
                        keep(new byte[]{(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        target.write(b, off, len);
                        keep(b, off, len);
                    }

                    @Override
                    public void flush() throws IOException {
                        target.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        target.close();
                    }

                    @Override
                    public boolean isReady() {
                        return target.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        target.setWriteListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String enc = getCharacterEncoding();
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), enc == null ? StandardCharsets.UTF_8 : charset(enc)));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            flushWriter();
            super.flushBuffer();
        }

        void flushWriter() {
            if (writer != null) {
                writer.flush();
            }
        }
    }
}
