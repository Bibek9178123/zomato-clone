package com.zomato.controller;

import com.zomato.config.StripeConfig;
import com.zomato.dto.request.PaymentRequest;
import com.zomato.dto.response.PaymentResponse;
import com.zomato.repository.UserRepository;
import com.zomato.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final UserRepository userRepository;
    private final StripeConfig stripeConfig;

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getStripeConfig() {
        return ResponseEntity.ok(Map.of(
                "publishableKey", stripeConfig.getPublishableKey() != null ? stripeConfig.getPublishableKey() : "",
                "currency", stripeConfig.getCurrency() != null ? stripeConfig.getCurrency() : "inr"
        ));
    }

    @PostMapping("/create-intent")
    public ResponseEntity<PaymentResponse> createPaymentIntent(
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        Long customerId = userRepository.findByEmail(authentication.getName()).orElseThrow().getId();
        return ResponseEntity.ok(paymentService.createPaymentIntent(request.getOrderId(), customerId));
    }

    /**
     * Stripe webhook — must receive the raw body (not parsed JSON) for signature verification.
     * Excluded from JWT filter and CSRF in SecurityConfig.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        paymentService.handleWebhook(payload, sigHeader);
        return ResponseEntity.ok("Webhook processed");
    }
}
