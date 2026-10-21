package com.we13ailab.ssedemo.util;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

public final class SseEmitterUtils {

    private SseEmitterUtils() {
    }

    public static boolean send(SseEmitter emitter, Object data) {
        return sendEvent(emitter, null, data);
    }

    public static boolean sendNamedEvent(SseEmitter emitter, String eventName, Object data) {
        return sendEvent(emitter, eventName, data);
    }

    public static boolean sendComment(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            return true;
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
            return false;
        }
    }

    private static boolean sendEvent(SseEmitter emitter, String eventName, Object data) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().data(data);
        if (eventName != null && !eventName.isBlank()) {
            event.name(eventName);
        }
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
            return false;
        }
    }
}
