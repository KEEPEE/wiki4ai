package com.wiki4ai.service;

import com.sun.net.httpserver.HttpServer;
import com.wiki4ai.config.EmbeddingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EmbeddingClient} against a stubbed sidecar
 * (JDK HttpServer — no Spring context, no real model).
 */
class EmbeddingClientTest {

    private HttpServer server;
    private int port;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private volatile String healthResponse = "{\"status\":\"ok\",\"model\":\"Qwen3-Embedding-0.6B\",\"dim\":1024}";
    private volatile boolean embedShouldFail = false;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/health", exchange -> {
            byte[] body = healthResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/embed", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (embedShouldFail) {
                byte[] err = "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            // Echo the number of texts as unit vectors of dim 4.
            String req = receivedBodies.get(receivedBodies.size() - 1);
            int textCount = countOccurrences(req, "\"texts\"") == 0 ? 1 : countTexts(req);
            StringBuilder sb = new StringBuilder("{\"vectors\":[");
            for (int i = 0; i < textCount; i++) {
                if (i > 0) sb.append(',');
                sb.append("[1.0,0.0,0.0,0.0]");
            }
            sb.append("],\"dim\":4,\"model\":\"stub\",\"truncated\":false}");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Count the elements of the "texts" array in a JSON request body. */
    private static int countTexts(String json) {
        int start = json.indexOf("\"texts\"");
        int open = json.indexOf('[', start);
        int close = json.indexOf(']', open);
        String inner = json.substring(open + 1, close);
        if (inner.isBlank()) {
            return 0;
        }
        return inner.split(",(?=\\s*\")", -1).length;
    }

    private EmbeddingClient client() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setBaseUrl("http://127.0.0.1:" + port);
        props.setConnectTimeoutMs(1000);
        props.setReadTimeoutMs(5000);
        props.setBatchSize(4);
        props.setUnavailableTtlMs(60_000); // long TTL so the cache does not expire mid-test
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
        return new EmbeddingClient(restClient, props);
    }

    @Test
    @DisplayName("toVectorLiteral renders a pgvector literal")
    void toVectorLiteralFormat() {
        float[] v = {0.5f, -1.0f, 0.123456789f};
        assertThat(EmbeddingClient.toVectorLiteral(v)).isEqualTo("[0.500000,-1.000000,0.123457]");
    }

    @Test
    @DisplayName("isAvailable is true when /health answers ok")
    void isAvailableTrue() {
        assertThat(client().isAvailable()).isTrue();
    }

    @Test
    @DisplayName("isAvailable is false and cached when the sidecar is unreachable")
    void isAvailableFalseWhenDown() {
        EmbeddingProperties props = new EmbeddingProperties();
        // Nothing listens on this port.
        props.setBaseUrl("http://127.0.0.1:1");
        props.setConnectTimeoutMs(300);
        props.setUnavailableTtlMs(60_000);
        RestClient restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        EmbeddingClient c = new EmbeddingClient(restClient, props);

        assertThat(c.isAvailable()).isFalse();
        // Second call must short-circuit from the cache (no 300 ms timeout wait).
        long start = System.nanoTime();
        assertThat(c.isAvailable()).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(250);
    }

    @Test
    @DisplayName("embedDocuments chunks into batches of <=4 and concatenates vectors")
    void embedDocumentsChunking() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            texts.add("text number " + i);
        }

        List<float[]> vectors = client().embedDocuments(texts);

        assertThat(vectors).hasSize(9);
        // 9 texts with batch size 4 → three HTTP calls: 4, 4, 1
        assertThat(receivedBodies).hasSize(3);
        assertThat(countTexts(receivedBodies.get(0))).isEqualTo(4);
        assertThat(countTexts(receivedBodies.get(1))).isEqualTo(4);
        assertThat(countTexts(receivedBodies.get(2))).isEqualTo(1);
        // Every request uses the document type (no instruction prefix server-side)
        receivedBodies.forEach(b -> assertThat(b).contains("\"type\":\"document\""));
    }

    @Test
    @DisplayName("embedQuery sends type=query")
    void embedQueryType() {
        float[] v = client().embedQuery("how does login work?");
        assertThat(v).hasSize(4);
        assertThat(receivedBodies).hasSize(1);
        assertThat(receivedBodies.get(0)).contains("\"type\":\"query\"");
        assertThat(receivedBodies.get(0)).contains("how does login work?");
    }

    @Test
    @DisplayName("embed throws EmbeddingUnavailableException on sidecar error")
    void embedFailure() {
        embedShouldFail = true;
        assertThatThrownBy(() -> client().embedDocuments(List.of("a", "b")))
                .isInstanceOf(EmbeddingClient.EmbeddingUnavailableException.class);
    }

    @Test
    @DisplayName("embed with empty list throws EmbeddingUnavailableException")
    void embedEmptyList() {
        assertThatThrownBy(() -> client().embedDocuments(List.of()))
                .isInstanceOf(EmbeddingClient.EmbeddingUnavailableException.class);
    }
}
