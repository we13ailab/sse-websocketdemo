package com.we13ailab.ssedemo;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class SseController {

    private final AtomicLong counter = new AtomicLong();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // 0L timeout = no automatic timeout; client controls lifetime
        SseEmitter emitter = new SseEmitter(0L);

        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            long value = counter.incrementAndGet();
            Map<String, Object> payload = Map.of(
                    "type", "tick",
                    "counter", value,
                    "timestamp", Instant.now().toString()
            );
            try {
                // Do not set a custom event name so the browser's
                // default `onmessage` handler receives these events.
                emitter.send(payload);
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        Runnable cleanup = () -> task.cancel(true);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        return emitter;
    }
}
