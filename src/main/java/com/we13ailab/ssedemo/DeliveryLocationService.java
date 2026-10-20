package com.we13ailab.ssedemo;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DeliveryLocationService {

    private final ConcurrentHashMap<String, DeliveryState> deliveries = new ConcurrentHashMap<>();

    public DeliveryLocation publish(DeliveryLocation location) {
        DeliveryState state = deliveries.computeIfAbsent(location.deliveryId(), ignored -> new DeliveryState());
        synchronized (state) {
            state.latestLocation = location;
            state.subscribers.removeIf(emitter -> !send(emitter, location));
        }
        return location;
    }

    public DeliveryLocation latestLocation(String deliveryId) {
        DeliveryState state = deliveries.get(deliveryId);
        return state == null ? null : state.latestLocation;
    }

    public SseEmitter subscribe(String deliveryId) {
        SseEmitter emitter = new SseEmitter(0L);
        DeliveryState state = deliveries.computeIfAbsent(deliveryId, ignored -> new DeliveryState());
        Runnable removeSubscriber = () -> state.subscribers.remove(emitter);

        emitter.onCompletion(removeSubscriber);
        emitter.onTimeout(removeSubscriber);
        emitter.onError(error -> removeSubscriber.run());

        synchronized (state) {
            state.subscribers.add(emitter);
            // Commit/flush the SSE response immediately, even if the driver has not
            // posted its first location yet. Otherwise clients can wait for headers.
            if (!sendComment(emitter) || (state.latestLocation != null && !send(emitter, state.latestLocation))) {
                state.subscribers.remove(emitter);
            }
        }
        return emitter;
    }

    private boolean send(SseEmitter emitter, DeliveryLocation location) {
        try {
            // No named event is used so browser EventSource.onmessage receives it.
            emitter.send(SseEmitter.event().data(location));
            return true;
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
            return false;
        }
    }

    private boolean sendComment(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            return true;
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
            return false;
        }
    }

    private static final class DeliveryState {
        private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private volatile DeliveryLocation latestLocation;
    }
}
