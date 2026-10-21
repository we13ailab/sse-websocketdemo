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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        HttpResponse<String> trackingPage = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/tracking.html"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, trackingPage.statusCode());
        assertTrue(trackingPage.body().contains("Brookefield"));
        assertTrue(trackingPage.body().contains("Varthur"));
        assertTrue(trackingPage.body().contains("Sobha Dream Acres"));
            assertTrue(trackingPage.body().contains("12.9877152, 77.7369166"));
            assertTrue(trackingPage.body().contains("eta-time"));
            assertTrue(trackingPage.body().contains("Heavy traffic"));
            int bannerIndex = trackingPage.body().indexOf("id=\"order-status-banner\"");
            assertTrue(bannerIndex > trackingPage.body().indexOf("<h2>Driver test simulator</h2>"));
            assertTrue(bannerIndex < trackingPage.body().indexOf("<h2>Customer map</h2>"));

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
            postLocation(deliveryId, 12.9877152, 77.7369166);
            assertTrue(firstEvent.get(5, TimeUnit.SECONDS).contains("\"latitude\":12.9877152"));

            Future<String> secondEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            postLocation(deliveryId, 12.9652094, 77.717936);
            String secondPayload = secondEvent.get(5, TimeUnit.SECONDS);
            assertTrue(secondPayload.contains("\"latitude\":12.9652094"));
            assertTrue(secondPayload.contains("\"longitude\":77.717936"));

            HttpResponse<String> latest = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/deliveries/" + deliveryId + "/location"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, latest.statusCode());
            assertTrue(latest.body().contains("\"latitude\":12.9652094"));

            Future<String> resetEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            HttpResponse<String> reset = postReset(deliveryId);
            assertEquals(200, reset.statusCode(), reset.body());
            assertTrue(resetEvent.get(5, TimeUnit.SECONDS).contains("\"type\":\"reset\""));

            HttpResponse<String> clearedLocation = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/deliveries/" + deliveryId + "/location"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, clearedLocation.statusCode());
            assertTrue(reset.body().contains("\"stage\":\"READY_FOR_PICKUP\""));
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    @Test
    void streamsOrderStatusesAndCompletesDeliveryAfterCorrectOtp() throws Exception {
        String deliveryId = "status-integration-test";
        HttpRequest eventsRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/deliveries/" + deliveryId + "/status/events"))
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
            Future<String> initialEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            assertTrue(initialEvent.get(5, TimeUnit.SECONDS).contains("\"stage\":\"READY_FOR_PICKUP\""));

            Future<String> pickedUpEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            assertEquals(200, postStatus(deliveryId, "picked-up", null).statusCode());
            assertTrue(pickedUpEvent.get(5, TimeUnit.SECONDS).contains("\"stage\":\"PICKED_UP\""));

            Future<String> onTheWayEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            assertEquals(200, postStatus(deliveryId, "on-the-way", null).statusCode());
            assertTrue(onTheWayEvent.get(5, TimeUnit.SECONDS).contains("\"stage\":\"ON_THE_WAY\""));

            Future<String> arrivedEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            HttpResponse<String> arrived = postStatus(deliveryId, "arrived", null);
            assertEquals(200, arrived.statusCode());
            String arrivalPayload = arrivedEvent.get(5, TimeUnit.SECONDS);
            assertTrue(arrivalPayload.contains("\"stage\":\"ARRIVED\""));
            Matcher otpMatcher = Pattern.compile("\"otp\":\"(\\d{4})\"").matcher(arrived.body());
            assertTrue(otpMatcher.find(), "arrival response should contain the customer OTP");
            String otp = otpMatcher.group(1);

            String wrongOtp = otp.equals("0000") ? "0001" : "0000";
            assertEquals(400, postStatus(deliveryId, "verify-otp", "{\"otp\":\"" + wrongOtp + "\"}").statusCode());

            Future<String> deliveredEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            HttpResponse<String> delivered = postStatus(
                    deliveryId,
                    "verify-otp",
                    "{\"otp\":\"" + otp + "\"}"
            );
            assertEquals(200, delivered.statusCode(), delivered.body());
            assertTrue(deliveredEvent.get(5, TimeUnit.SECONDS).contains("\"stage\":\"DELIVERED\""));
            assertTrue(delivered.body().contains("Order delivered successfully."));

            Future<String> resetEvent = readerExecutor.submit(() -> readNextDataEvent(reader));
            HttpResponse<String> reset = postReset(deliveryId);
            assertEquals(200, reset.statusCode(), reset.body());
            assertTrue(resetEvent.get(5, TimeUnit.SECONDS).contains("\"stage\":\"READY_FOR_PICKUP\""));
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

    private HttpResponse<String> postStatus(String deliveryId, String action, String jsonBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/deliveries/" + deliveryId + "/status/" + action));
        if (jsonBody == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postReset(String deliveryId) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/deliveries/" + deliveryId + "/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
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
