package com.we13ailab.ssedemo.controller;

import com.we13ailab.ssedemo.dto.request.OtpVerificationRequest;
import com.we13ailab.ssedemo.dto.response.DeliveryStatus;
import com.we13ailab.ssedemo.service.DeliveryStatusService;

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

@RestController
@RequestMapping("/api/deliveries/{deliveryId}/status")
public class DeliveryStatusController {

    private final DeliveryStatusService statusService;

    public DeliveryStatusController(DeliveryStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping
    public DeliveryStatus currentStatus(@PathVariable String deliveryId) {
        return statusService.currentStatus(requireDeliveryId(deliveryId));
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusEvents(@PathVariable String deliveryId) {
        return statusService.subscribe(requireDeliveryId(deliveryId));
    }

    @PostMapping("/picked-up")
    public DeliveryStatus pickedUp(@PathVariable String deliveryId) {
        return changeStatus(deliveryId, () -> statusService.markPickedUp(deliveryId));
    }

    @PostMapping("/on-the-way")
    public DeliveryStatus onTheWay(@PathVariable String deliveryId) {
        return changeStatus(deliveryId, () -> statusService.markOnTheWay(deliveryId));
    }

    @PostMapping("/arrived")
    public DeliveryStatus arrived(@PathVariable String deliveryId) {
        return changeStatus(deliveryId, () -> statusService.markArrived(deliveryId));
    }

    @PostMapping("/verify-otp")
    public DeliveryStatus verifyOtp(
            @PathVariable String deliveryId,
            @RequestBody OtpVerificationRequest request
    ) {
        return changeStatus(deliveryId, () -> statusService.verifyOtp(deliveryId, request.otp()));
    }

    private DeliveryStatus changeStatus(String deliveryId, StatusChange change) {
        requireDeliveryId(deliveryId);
        try {
            return change.apply();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private String requireDeliveryId(String deliveryId) {
        if (deliveryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deliveryId must not be blank");
        }
        return deliveryId;
    }

    @FunctionalInterface
    private interface StatusChange {
        DeliveryStatus apply();
    }
}
