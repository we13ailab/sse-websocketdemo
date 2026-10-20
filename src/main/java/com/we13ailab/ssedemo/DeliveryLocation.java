package com.we13ailab.ssedemo;

import java.time.Instant;

public record DeliveryLocation(
        String deliveryId,
        double latitude,
        double longitude,
        Instant timestamp
) {
}
