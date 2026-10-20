package com.we13ailab.ssedemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WsTickHandler extends TextWebSocketHandler {

    private final AtomicLong counter = new AtomicLong();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sendJson(session, Map.of(
                "type", "info",
                "message", "Connected to WebSocket server",
                "timestamp", Instant.now().toString()
        ));

        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            long value = counter.incrementAndGet();
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "tick");
            payload.put("counter", value);
            payload.put("timestamp", Instant.now().toString());
            try {
                sendJson(session, payload);
            } catch (IOException e) {
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        tasks.put(session.getId(), task);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "echo");
        payload.put("text", message.getPayload());
        payload.put("timestamp", Instant.now().toString());
        sendJson(session, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        ScheduledFuture<?> task = tasks.remove(session.getId());
        if (task != null) {
            task.cancel(true);
        }
    }

    private void sendJson(WebSocketSession session, Map<String, ?> payload) throws JsonProcessingException, IOException {
        if (!session.isOpen()) {
            return;
        }
        String json = objectMapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }
}
