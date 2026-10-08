package com.zomato.dto.response;

import com.zomato.model.enums.PaymentStatus;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class PaymentResponse {
    private Long id;
    private PaymentStatus status;
    private BigDecimal amount;
    private String currency;
    private String stripePaymentIntentId;
    private String clientSecret;
}
