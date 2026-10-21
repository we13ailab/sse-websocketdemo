package com.we13ailab.ssedemo.dto.response;

import com.we13ailab.ssedemo.model.DeliveryStage;

import java.time.Instant;

public record DeliveryStatus(
        String deliveryId,
        DeliveryStage stage,
        String message,
        String otp,
        Instant updatedAt
) {
}
