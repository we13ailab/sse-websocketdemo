package com.we13ailab.ssedemo.service;

import com.we13ailab.ssedemo.dto.response.DeliveryLocation;
import com.we13ailab.ssedemo.util.SseEmitterUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DeliveryLocationService {

    private final ConcurrentHashMap<String, DeliveryState> deliveries = new ConcurrentHashMap<>();

    public DeliveryLocation publish(DeliveryLocation location) {
        DeliveryState state = deliveries.computeIfAbsent(location.deliveryId(), ignored -> new DeliveryState());
        synchronized (state) {
            state.latestLocation = location;
            state.subscribers.removeIf(emitter -> !SseEmitterUtils.send(emitter, location));
        }
        return location;
    }

    public DeliveryLocation latestLocation(String deliveryId) {
        DeliveryState state = deliveries.get(deliveryId);
        return state == null ? null : state.latestLocation;
    }

    public void reset(String deliveryId) {
        DeliveryState state = deliveries.computeIfAbsent(deliveryId, ignored -> new DeliveryState());
        synchronized (state) {
            state.latestLocation = null;
            state.subscribers.removeIf(emitter -> !sendReset(emitter, deliveryId));
        }
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
            if (!SseEmitterUtils.sendComment(emitter)
                    || (state.latestLocation != null && !SseEmitterUtils.send(emitter, state.latestLocation))) {
                state.subscribers.remove(emitter);
            }
        }
        return emitter;
    }

    private boolean sendReset(SseEmitter emitter, String deliveryId) {
        return SseEmitterUtils.sendNamedEvent(
                emitter,
                "reset",
                Map.of("type", "reset", "deliveryId", deliveryId)
        );
    }

    private static final class DeliveryState {
        private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private volatile DeliveryLocation latestLocation;
    }
}
