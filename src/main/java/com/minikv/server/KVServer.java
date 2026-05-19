package com.minikv.server;

import com.minikv.api.StorageEngine;
import com.minikv.model.Entry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class KVServer {
    private final HttpServer httpServer;
    private final StorageEngine engine;
    private final KVAuth auth;

    public KVServer(StorageEngine engine, KVAuth auth, int port) throws IOException {
        this.engine = engine;
        this.auth   = auth;
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.httpServer.createContext("/put",         this::handlePut);
        this.httpServer.createContext("/get",         this::handleGet);
        this.httpServer.createContext("/delete",      this::handleDelete);
        this.httpServer.createContext("/scan",        this::handleScan);
        this.httpServer.createContext("/stats",       this::handleStats);
        this.httpServer.createContext("/metrics",     this::handleMetrics);
        this.httpServer.createContext("/auth/rotate", this::handleRotate);
        this.httpServer.createContext("/ui",          this::handleUI);
        this.httpServer.createContext("/",            this::handleHelp);
    }

    public void start() { httpServer.start(); }
    public void stop()  { httpServer.stop(0); }

    private boolean unauthorized(HttpExchange ex) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (auth.isValid(header)) return false;
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"MiniKV\"");
        respond(ex, 401, json("error", "unauthorized", "hint", "Set Authorization: Bearer <api-key>"));
        return true;
    }

    private void handlePut(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        try {
            Map<String, String> params = queryParams(ex);
            String key = required(params, "key"), value = required(params, "value");
            long ttl = params.containsKey("ttl") ? Long.parseLong(params.get("ttl")) : 0;
            engine.put(key, value.getBytes(StandardCharsets.UTF_8), ttl);
            respond(ex, 200, ttl > 0
                    ? json("status", "ok", "key", key, "ttl_seconds", String.valueOf(ttl))
                    : json("status", "ok", "key", key));
        } catch (IllegalArgumentException e) { respond(ex, 400, json("error", e.getMessage()));
        } catch (Exception e) { respond(ex, 500, json("error", e.getMessage())); }
    }

    private void handleGet(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        try {
            String key = required(queryParams(ex), "key");
            Optional<byte[]> val = engine.get(key);
            if (val.isPresent()) respond(ex, 200, json("key", key, "value", new String(val.get(), StandardCharsets.UTF_8)));
            else respond(ex, 404, json("error", "not found or expired", "key", key));
        } catch (IllegalArgumentException e) { respond(ex, 400, json("error", e.getMessage()));
        } catch (Exception e) { respond(ex, 500, json("error", e.getMessage())); }
    }

    private void handleDelete(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        try {
            String key = required(queryParams(ex), "key");
            engine.delete(key);
            respond(ex, 200, json("status", "ok", "key", key));
        } catch (IllegalArgumentException e) { respond(ex, 400, json("error", e.getMessage()));
        } catch (Exception e) { respond(ex, 500, json("error", e.getMessage())); }
    }

    private void handleScan(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        try {
            Map<String, String> params = queryParams(ex);
            Iterator<Entry> iter = engine.scan(required(params, "from"), required(params, "to"));
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            while (iter.hasNext()) {
                Entry e = iter.next();
                if (!first) sb.append(",");
                sb.append("\n  ").append(json("key", e.getKey(),
                        "value", new String(e.getValue(), StandardCharsets.UTF_8)));
                first = false;
            }
            respond(ex, 200, sb.append(first ? "]" : "\n]").toString());
        } catch (IllegalArgumentException e) { respond(ex, 400, json("error", e.getMessage()));
        } catch (Exception e) { respond(ex, 500, json("error", e.getMessage())); }
    }

    private void handleStats(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        respond(ex, 200, "{\"engine\": \"MiniKV LSM-Tree\", \"status\": \"running\"}");
    }

    // Public — no auth required for Prometheus scraping
    private void handleMetrics(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", TextFormat.CONTENT_TYPE_004);
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody();
             OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
    }

    private void handleRotate(HttpExchange ex) throws IOException {
        if (unauthorized(ex)) return;
        try {
            String newKey = auth.rotate();
            System.out.println("[AUTH] API key rotated. New key: " + newKey);
            respond(ex, 200, json("status", "ok", "api_key", newKey));
        } catch (Exception e) { respond(ex, 500, json("error", e.getMessage())); }
    }

    private void handleUI(HttpExchange ex) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("index.html")) {
            if (is == null) { respond(ex, 404, json("error", "UI not found")); return; }
            byte[] html = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(html); }
        }
    }

    private void handleHelp(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"endpoints\": [\"PUT /put\", \"GET /get\", \"DELETE /delete\", \"GET /scan\", \"GET /stats\", \"GET /metrics\", \"GET /ui\"]}");
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> params = new HashMap<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            try (InputStream is = ex.getRequestBody()) {
                query = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {}
        }
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static String required(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing: " + name);
        return v;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length - 1; i += 2) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(escape(kv[i])).append("\": \"")
              .append(escape(kv[i+1])).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r");
    }
}
