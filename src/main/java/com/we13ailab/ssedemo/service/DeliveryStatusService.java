package com.we13ailab.ssedemo.service;

import com.we13ailab.ssedemo.dto.response.DeliveryStatus;
import com.we13ailab.ssedemo.model.DeliveryStage;
import com.we13ailab.ssedemo.util.SseEmitterUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DeliveryStatusService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConcurrentHashMap<String, DeliveryState> deliveries = new ConcurrentHashMap<>();

    public DeliveryStatus currentStatus(String deliveryId) {
        DeliveryState state = stateFor(deliveryId);
        synchronized (state) {
            return ensureInitialStatus(deliveryId, state);
        }
    }

    public DeliveryStatus reset(String deliveryId) {
        DeliveryState state = stateFor(deliveryId);
        synchronized (state) {
            return updateStatus(deliveryId, state, DeliveryStage.READY_FOR_PICKUP, null);
        }
    }

    public SseEmitter subscribe(String deliveryId) {
        SseEmitter emitter = new SseEmitter(0L);
        DeliveryState state = stateFor(deliveryId);
        Runnable removeSubscriber = () -> state.subscribers.remove(emitter);
        emitter.onCompletion(removeSubscriber);
        emitter.onTimeout(removeSubscriber);
        emitter.onError(error -> removeSubscriber.run());

        synchronized (state) {
            DeliveryStatus current = ensureInitialStatus(deliveryId, state);
            state.subscribers.add(emitter);
            if (!SseEmitterUtils.sendComment(emitter) || !SseEmitterUtils.send(emitter, current)) {
                state.subscribers.remove(emitter);
            }
        }
        return emitter;
    }

    public DeliveryStatus markPickedUp(String deliveryId) {
        return transition(deliveryId, DeliveryStage.READY_FOR_PICKUP, DeliveryStage.PICKED_UP, null);
    }

    public DeliveryStatus markOnTheWay(String deliveryId) {
        return transition(deliveryId, DeliveryStage.PICKED_UP, DeliveryStage.ON_THE_WAY, null);
    }

    public DeliveryStatus markArrived(String deliveryId) {
        String otp = String.format("%04d", RANDOM.nextInt(10_000));
        return transition(deliveryId, DeliveryStage.ON_THE_WAY, DeliveryStage.ARRIVED, otp);
    }

    public DeliveryStatus verifyOtp(String deliveryId, String otp) {
        DeliveryState state = stateFor(deliveryId);
        synchronized (state) {
            DeliveryStatus current = ensureInitialStatus(deliveryId, state);
            if (current.stage() != DeliveryStage.ARRIVED) {
                throw new IllegalStateException("OTP can only be verified after the driver arrives");
            }
            if (otp == null || !otp.equals(current.otp())) {
                throw new IllegalArgumentException("Incorrect OTP. Ask the customer to share the delivery OTP.");
            }
            return updateStatus(deliveryId, state, DeliveryStage.DELIVERED, null);
        }
    }

    private DeliveryStatus transition(
            String deliveryId,
            DeliveryStage expected,
            DeliveryStage next,
            String otp
    ) {
        DeliveryState state = stateFor(deliveryId);
        synchronized (state) {
            DeliveryStatus current = ensureInitialStatus(deliveryId, state);
            if (current.stage() != expected) {
                throw new IllegalStateException("Cannot change status from " + current.stage() + " to " + next);
            }
            return updateStatus(deliveryId, state, next, otp);
        }
    }

    private DeliveryStatus updateStatus(String deliveryId, DeliveryState state, DeliveryStage stage, String otp) {
        DeliveryStatus status = new DeliveryStatus(deliveryId, stage, messageFor(stage), otp, Instant.now());
        state.currentStatus = status;
        state.subscribers.removeIf(emitter -> !SseEmitterUtils.send(emitter, status));
        return status;
    }

    private DeliveryStatus ensureInitialStatus(String deliveryId, DeliveryState state) {
        if (state.currentStatus == null) {
            state.currentStatus = new DeliveryStatus(
                    deliveryId,
                    DeliveryStage.READY_FOR_PICKUP,
                    messageFor(DeliveryStage.READY_FOR_PICKUP),
                    null,
                    Instant.now()
            );
        }
        return state.currentStatus;
    }

    private DeliveryState stateFor(String deliveryId) {
        return deliveries.computeIfAbsent(deliveryId, ignored -> new DeliveryState());
    }

    private String messageFor(DeliveryStage stage) {
        return switch (stage) {
            case READY_FOR_PICKUP -> "Order is ready for pickup.";
            case PICKED_UP -> "Order picked up by the delivery person.";
            case ON_THE_WAY -> "Your order is on the way.";
            case ARRIVED -> "Your delivery person has reached your location. Collect your order and share the OTP with the driver.";
            case DELIVERED -> "Order delivered successfully.";
        };
    }

    private static final class DeliveryState {
        private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private volatile DeliveryStatus currentStatus;
    }
}
