package com.hospio.property;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A local HTTP server for client tests: answers each path with a fixed status and JSON, and keeps what was asked. */
public final class StubHttp implements AutoCloseable {

    /** One request as it arrived: method, path with query, body. */
    public record Seen(String method, String uri, String body) {
    }

    private final HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    private final Map<String, int[]> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    public StubHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (seen) {
                seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), body));
            }
            String path = ex.getRequestURI().getPath();
            String answer = bodies.get(path);
            int status = answer == null ? 404 : statuses.get(path)[0];
            byte[] out = (answer == null ? "{}" : answer).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    /** Answers {@code path} (no query) with this status and JSON. */
    public StubHttp on(String path, int status, String json) {
        statuses.put(path, new int[]{status});
        bodies.put(path, json);
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    public Seen last() {
        List<Seen> all = seen();
        return all.get(all.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
