package com.zomato.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.zomato.config.StripeConfig;
import com.zomato.dto.response.PaymentResponse;
import com.zomato.exception.BusinessException;
import com.zomato.exception.PaymentException;
import com.zomato.exception.ResourceNotFoundException;
import com.zomato.model.Order;
import com.zomato.model.Payment;
import com.zomato.model.enums.OrderStatus;
import com.zomato.model.enums.PaymentMethod;
import com.zomato.model.enums.PaymentStatus;
import com.zomato.repository.OrderRepository;
import com.zomato.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final StripeConfig stripeConfig;

    @Transactional
    public PaymentResponse createPaymentIntent(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        paymentRepository.findByOrderId(orderId).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.COMPLETED) {
                throw new BusinessException("Order is already paid");
            }
        });

        String idempotencyKey = "payment-" + orderId + "-" + customerId;

        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existingPayment -> {
                    log.info("Returning existing payment intent for order {}", orderId);
                    return mapToPaymentResponse(existingPayment);
                })
                .orElseGet(() -> {
                    try {
                        Stripe.apiKey = stripeConfig.getSecretKey();

                        long amountInPaise = order.getTotalAmount()
                                .multiply(BigDecimal.valueOf(100)).longValue();

                        Map<String, String> metadata = new HashMap<>();
                        metadata.put("orderId", orderId.toString());
                        metadata.put("customerId", customerId.toString());
                        metadata.put("orderNumber", order.getOrderNumber());

                        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                                .setAmount(amountInPaise)
                                .setCurrency("inr")
                                .putAllMetadata(metadata)
                                .addPaymentMethodType("card")
                                .build();

                        RequestOptions requestOptions = RequestOptions.builder()
                                .setIdempotencyKey(idempotencyKey)
                                .build();

                        PaymentIntent paymentIntent = PaymentIntent.create(params, requestOptions);

                        Payment payment = new Payment();
                        payment.setStripePaymentIntentId(paymentIntent.getId());
                        payment.setIdempotencyKey(idempotencyKey);
                        payment.setAmount(order.getTotalAmount());
                        payment.setCurrency("INR");
                        payment.setStatus(PaymentStatus.PENDING);
                        payment.setMethod(PaymentMethod.CARD);
                        payment.setOrder(order);

                        Payment saved = paymentRepository.save(payment);

                        auditService.log("PAYMENT", saved.getId(), "PAYMENT_INTENT_CREATED",
                                order.getCustomer().getEmail(),
                                "Payment intent: " + paymentIntent.getId(), "");

                        PaymentResponse response = mapToPaymentResponse(saved);
                        response.setClientSecret(paymentIntent.getClientSecret());
                        return response;

                    } catch (StripeException e) {
                        log.error("Stripe error creating payment intent for order {}: {}",
                                orderId, e.getMessage());
                        throw new PaymentException("Failed to create payment intent: " + e.getMessage());
                    }
                });
    }

    @Transactional
    public void handleWebhook(String payload, String sigHeader) {
        try {
            Stripe.apiKey = stripeConfig.getSecretKey();
            Event event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
            log.info("Received Stripe webhook event: {}", event.getType());
            switch (event.getType()) {
                case "payment_intent.succeeded" -> handlePaymentSuccess(event);
                case "payment_intent.payment_failed" -> handlePaymentFailure(event);
                case "charge.refunded" -> handleRefundEvent(event);
                default -> log.debug("Unhandled webhook event type: {}", event.getType());
            }
        } catch (SignatureVerificationException e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            throw new BusinessException("Invalid webhook signature");
        }
    }

    private void handlePaymentSuccess(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(stripeObject -> {
            if (stripeObject instanceof PaymentIntent paymentIntent) {
                paymentRepository.findByStripePaymentIntentId(paymentIntent.getId())
                        .ifPresent(payment -> {
                            payment.setStatus(PaymentStatus.COMPLETED);
                            paymentRepository.save(payment);
                            Order order = payment.getOrder();
                            order.setStatus(OrderStatus.CONFIRMED);
                            orderRepository.save(order);
                            log.info("Payment succeeded for order: {}", order.getOrderNumber());
                            auditService.log("PAYMENT", payment.getId(), "PAYMENT_SUCCEEDED",
                                    "SYSTEM",
                                    "Payment completed for order: " + order.getOrderNumber(), "");
                        });
            }
        });
    }

    private void handlePaymentFailure(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(stripeObject -> {
            if (stripeObject instanceof PaymentIntent paymentIntent) {
                paymentRepository.findByStripePaymentIntentId(paymentIntent.getId())
                        .ifPresent(payment -> {
                            payment.setStatus(PaymentStatus.FAILED);
                            payment.setFailureReason(paymentIntent.getLastPaymentError() != null
                                    ? paymentIntent.getLastPaymentError().getMessage()
                                    : "Unknown error");
                            paymentRepository.save(payment);
                            log.warn("Payment failed for intent: {}", paymentIntent.getId());
                        });
            }
        });
    }

    private void handleRefundEvent(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(stripeObject -> {
            if (stripeObject instanceof Charge charge) {
                log.info("Refund processed for charge: {}", charge.getId());
            }
        });
    }

    @Transactional
    public void initiateRefund(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        try {
            Stripe.apiKey = stripeConfig.getSecretKey();
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(payment.getStripePaymentIntentId())
                    .build();
            Refund refund = Refund.create(params);
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setRefundId(refund.getId());
            paymentRepository.save(payment);
            Order order = payment.getOrder();
            order.setStatus(OrderStatus.REFUNDED);
            orderRepository.save(order);
            log.info("Refund initiated for payment: {}, refundId: {}", paymentId, refund.getId());
            auditService.log("PAYMENT", paymentId, "REFUND_INITIATED", "SYSTEM",
                    "Refund: " + refund.getId(), "");
        } catch (StripeException e) {
            log.error("Failed to initiate refund for payment {}: {}", paymentId, e.getMessage());
            throw new PaymentException("Refund failed: " + e.getMessage());
        }
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        PaymentResponse response = new PaymentResponse();
        response.setId(payment.getId());
        response.setStatus(payment.getStatus());
        response.setAmount(payment.getAmount());
        response.setCurrency(payment.getCurrency());
        response.setStripePaymentIntentId(payment.getStripePaymentIntentId());
        return response;
    }
}
