package com.hospio.housekeeping.client;

import com.hospio.common.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.util.ReflectionTestUtils;

/** A local HTTP server answering fixed JSON per path, standing in for another Hospio service in client tests. */
final class StubServer implements AutoCloseable {

    record Reply(int status, String json) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", ex -> {
            Reply r = replies.getOrDefault(ex.getRequestURI().getPath(), new Reply(404, "{\"status\":false,\"message\":\"Not found\"}"));
            byte[] out = r.json().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    StubServer reply(String path, int status, String json) {
        replies.put(path, new Reply(status, json));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A URL nothing listens on, for "the other service is down". */
    static String downUrl() {
        return "http://127.0.0.1:1";
    }

    static JwtUtil jwt() {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(util, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(util, "buildParser");
        return util;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
