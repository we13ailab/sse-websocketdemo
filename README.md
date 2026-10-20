## Spring SSE vs WebSocket Demo

This project is a minimal Spring Boot application that exposes two real-time APIs side by side so you can compare **Server-Sent Events (SSE)** and **WebSockets**.

- SSE endpoint: `GET /sse`
- WebSocket endpoint: `ws://localhost:8080/ws`
- Demo UI: `GET /` (serves `src/main/resources/static/index.html`)

Spring Boot version: `4.1.1`
Java version: `21` (see `pom.xml` properties)

---

## Running the Application

From the project root:

```bash
./mvnw spring-boot:run
```

If you use a system Maven instead of the wrapper:

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` by default.

---

## Demo UI

Open:

```text
http://localhost:8080/
```

The page has two panels:

1. **SSE panel**
   - Buttons: `Connect`, `Disconnect`.
   - On connect, the browser opens an `EventSource` to `/sse` and logs messages.

2. **WebSocket panel**
   - Buttons: `Connect`, `Disconnect`, `Send test message`.
   - On connect, the browser opens a `WebSocket` to `/ws`, logs messages, and you can send a sample message to see the echo.

This lets you see the behavior, payloads, and timing differences side by side.

The food-delivery location demo is available at:

```text
http://localhost:8080/tracking.html
```

---

## Live Food-Delivery Tracking Demo

The tracking flow uses a driver-to-server HTTP `POST` and server-to-customer SSE stream:

1. The driver simulator posts a latitude and longitude every second.
2. Spring stores the latest location per delivery in memory.
3. Spring broadcasts each update to customer browsers connected to that delivery's SSE stream.
4. The tracking page moves the driver marker on a Leaflet map.

The map uses Leaflet with OpenStreetMap's free standard tiles and displays the required map attribution. It requires internet access in the browser. The public OpenStreetMap tile service is intended for modest demo use; follow its tile usage policy and use a hosted tile provider for production or significant traffic.

### Try it in the browser

1. Start the app and open `http://localhost:8080/tracking.html`.
2. Keep the default delivery ID `demo-order-1` or choose another ID.
3. Click **Start simulated driver**. The sample driver sends a new point every second.
4. Watch the marker and coordinates update on the map.
5. To see separate driver and customer clients, open the same tracking URL in two tabs with the same delivery ID. Start the simulator in one tab; the other tab receives the live locations.

The sample coordinates follow a short route near Seattle. They are test coordinates, not actual driver GPS data.

### Location APIs

Post a driver location:

```bash
curl -X POST http://localhost:8080/api/deliveries/demo-order-1/location -H 'Content-Type: application/json' -d '{"latitude":47.6062,"longitude":-122.3321}'
```

Get the latest location:

```bash
curl http://localhost:8080/api/deliveries/demo-order-1/location
```

Watch the live event stream (run in a separate terminal, then post locations):

```bash
curl -N http://localhost:8080/api/deliveries/demo-order-1/events
```

Each event contains JSON similar to:

```json
{
  "deliveryId": "demo-order-1",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "timestamp": "2026-10-20T12:00:00Z"
}
```

Coordinates outside latitude `-90..90` or longitude `-180..180` are rejected. `GET /location` returns `404` until the first location has been posted.

### Automated real-time update test

The integration test opens an SSE connection, posts two different locations, and verifies that both arrive on that same connection in order. It also checks that the latest-location endpoint returns the second point:

```bash
./mvnw -Dtest=DeliveryTrackingIntegrationTest test
```

Locations and active SSE subscribers are held in application memory. They are lost on restart and are not shared between multiple app instances. This demo has no authentication; use authenticated delivery/customer access and durable storage before using a similar flow in production.

---

## SSE Endpoint (`/sse`)

**Code:** `SseController` (`src/main/java/com/we13ailab/ssedemo/SseController.java`)

Signature:

```java
@GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream() { ... }
```

Behavior:

- When a client calls `GET /sse`, the controller returns an `SseEmitter` with an infinite timeout.
- A scheduled task sends a JSON object every second:

  ```json
  {
    "type": "tick",
    "counter": 1,
    "timestamp": "2024-01-01T12:00:00Z"
  }
  ```

- The emitter is cleaned up on completion, timeout, or error.

### How to Use SSE

Browser (built-in API):

```js
const es = new EventSource('/sse');

es.onmessage = (event) => {
  console.log('SSE:', event.data);
};

es.onerror = () => {
  console.log('SSE error or disconnected');
};
```

`EventSource` handles reconnection automatically by default.

Command line (one-shot request, for debugging headers):

```bash
curl -N http://localhost:8080/sse
```

You will see a continuous stream of events.

### When to Use SSE

SSE is a good fit when:

- You only need **server → client** updates (unidirectional).
- You want a simple API on top of HTTP and easy browser support.
- You are pushing events like:
  - Live logs / status updates.
  - Progress of a long-running job.
  - Live scores or basic real-time dashboards.

SSE is **not** ideal when the client also needs to send arbitrary messages to the server over the same channel; use WebSockets instead.

---

## WebSocket Endpoint (`/ws`)

**Code:**

- Config: `WebSocketConfig` (`src/main/java/com/we13ailab/ssedemo/WebSocketConfig.java`)
- Handler: `WsTickHandler` (`src/main/java/com/we13ailab/ssedemo/WsTickHandler.java`)

Registration:

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsTickHandler, "/ws").setAllowedOrigins("*");
    }
}
```

Handler behavior (`WsTickHandler`):

- On connection:
  - Sends an initial `info` message to confirm the connection.
  - Starts a per-connection task that sends a `tick` message every second:

    ```json
    {
      "type": "tick",
      "counter": 1,
      "timestamp": "2024-01-01T12:00:00Z"
    }
    ```

- On text message from the client:
  - Echoes the message back:

    ```json
    {
      "type": "echo",
      "text": "your client message",
      "timestamp": "..."
    }
    ```

- On close:
  - Cancels that connection's scheduled tick task.

### How to Use WebSockets

Browser:

```js
const socket = new WebSocket('ws://localhost:8080/ws');

socket.onopen = () => {
  console.log('WebSocket opened');
  socket.send('Hello from browser');
};

socket.onmessage = (event) => {
  console.log('WS message:', event.data);
};

socket.onclose = () => {
  console.log('WebSocket closed');
};
```

Command line (example using `wscat`, if installed):

```bash
wscat -c ws://localhost:8080/ws
```

You will see the `info` and `tick` messages, and anything you type will be echoed back.

### When to Use WebSockets

WebSockets are a good fit when:

- You need **full duplex** communication (client ↔ server).
- You are building interactive applications:
  - Chat / messaging.
  - Multiplayer games.
  - Collaborative editors.
  - Dashboards where the client also sends commands or filters.

WebSockets give more control and flexibility, but require explicit reconnection logic and a bit more server-side setup.

---

## SSE vs WebSocket – Summary

**Protocol & Transport**

- **SSE**: HTTP streaming with `Content-Type: text/event-stream`.
- **WebSocket**: Separate WebSocket protocol after an HTTP handshake.

**Directionality**

- **SSE**: Server → client only.
- **WebSocket**: Client ↔ server.

**Browser Support**

- **SSE**: Native via `EventSource` in modern browsers.
- **WebSocket**: Native via `WebSocket` API in modern browsers.

**Reconnection**

- **SSE**: Built-in reconnection in browsers, supports `Last-Event-ID`.
- **WebSocket**: You typically implement reconnection logic yourself.

**Best Use Cases**

- **SSE**: One-way real-time updates, notifications, progress, basic dashboards.
- **WebSocket**: Two-way, low-latency interactive features.

This repository is intentionally small so you can use it as a reference or a starting point when deciding between SSE and WebSockets in a Spring Boot application.
