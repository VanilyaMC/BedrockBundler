package dev.bedrockbundler.paper.deploy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bedrockbundler.common.spec.Bundle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Serves the bundle over HTTP so a Geyser instance on another machine (proxy or standalone) can download it
 * when it starts. Requests must carry {@code Authorization: Bearer <token>}.
 */
public final class HttpDeployer implements Deployer {

    public static final String BUNDLE_PATH = "/bedrockbundler/bundle";
    public static final String HASH_PATH = "/bedrockbundler/hash";

    private final HttpServer server;
    private final byte[] token;
    private final String address;
    private volatile byte[] zip;
    private volatile String hash = "";

    public HttpDeployer(String bind, int port, String token) throws IOException {
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("sync.http.token must be at least 16 characters");
        }
        this.token = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.address = bind + ":" + port;
        server.createContext(BUNDLE_PATH, exchange -> respond(exchange, zip, "application/zip"));
        server.createContext(HASH_PATH, exchange -> respond(exchange, hash.getBytes(StandardCharsets.UTF_8), "text/plain"));
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockBundler-HTTP");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    @Override
    public String describe() {
        return "HTTP endpoint http://" + address + BUNDLE_PATH;
    }

    @Override
    public void deploy(Path bundleDirectory) throws IOException {
        Bundle bundle = Bundle.read(bundleDirectory);
        zip = Bundle.zip(bundleDirectory);
        hash = bundle == null ? "" : bundle.hash();
    }

    private void respond(HttpExchange exchange, byte[] body, String contentType) throws IOException {
        try (exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), token)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("X-BedrockBundler-Hash", hash);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
