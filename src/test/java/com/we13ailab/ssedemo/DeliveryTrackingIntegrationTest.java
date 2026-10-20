package com.we13ailab.ssedemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.shutdown=immediate"
)
class DeliveryTrackingIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void streamsEachNewDriverLocationToConnectedCustomer() throws Exception {
        String deliveryId = "tracking-integration-test";
        URI eventsUri = URI.create("http://localhost:" + port
                + "/api/deliveries/" + deliveryId + "/events");
        HttpRequest eventsRequest = HttpRequest.newBuilder(eventsUri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<InputStream> events = httpClient.send(
                eventsRequest,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, events.statusCode());

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try (InputStream body = events.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            Future<String> firstEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            postLocation(deliveryId, 47.6062, -122.3321);
            assertTrue(firstEvent.get(5, TimeUnit.SECONDS).contains("\"latitude\":47.6062"));

            Future<String> secondEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            postLocation(deliveryId, 47.607, -122.3309);
            String secondPayload = secondEvent.get(5, TimeUnit.SECONDS);
            assertTrue(secondPayload.contains("\"latitude\":47.607"));
            assertTrue(secondPayload.contains("\"longitude\":-122.3309"));

            HttpResponse<String> latest = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/deliveries/" + deliveryId + "/location"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, latest.statusCode());
            assertTrue(latest.body().contains("\"latitude\":47.607"));
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private void postLocation(String deliveryId, double latitude, double longitude) throws Exception {
        String json = "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}";
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/deliveries/" + deliveryId + "/location"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), response.body());
    }

    private String readNextDataEvent(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
        }
        throw new IllegalStateException("SSE stream ended before receiving a location event");
    }
}
