package com.zomato.dto.response;

import com.zomato.model.enums.OrderStatus;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private String deliveryAddress;
    private LocalDateTime estimatedDeliveryTime;
    private LocalDateTime deliveredAt;
    private String promoCode;
    private String notes;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;
}
