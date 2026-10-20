package com.we13ailab.ssedemo;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

@RestController
@RequestMapping("/api/deliveries/{deliveryId}")
public class DeliveryTrackingController {

    private final DeliveryLocationService locationService;

    public DeliveryTrackingController(DeliveryLocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping("/location")
    public DeliveryLocation updateLocation(
            @PathVariable String deliveryId,
            @RequestBody LocationRequest request
    ) {
        if (deliveryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deliveryId must not be blank");
        }
        if (!Double.isFinite(request.latitude()) || request.latitude() < -90 || request.latitude() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude must be between -90 and 90");
        }
        if (!Double.isFinite(request.longitude()) || request.longitude() < -180 || request.longitude() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "longitude must be between -180 and 180");
        }

        DeliveryLocation location = new DeliveryLocation(
                deliveryId,
                request.latitude(),
                request.longitude(),
                Instant.now()
        );
        return locationService.publish(location);
    }

    @GetMapping("/location")
    public DeliveryLocation latestLocation(@PathVariable String deliveryId) {
        DeliveryLocation location = locationService.latestLocation(deliveryId);
        if (location == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No location found for delivery");
        }
        return location;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter locationEvents(@PathVariable String deliveryId) {
        return locationService.subscribe(deliveryId);
    }
}
