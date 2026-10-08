package com.zomato.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class PlaceOrderRequest {
    @NotNull private Long restaurantId;
    @NotEmpty private List<OrderItemRequest> items;
    @NotNull private String deliveryAddress;
    private Double deliveryLatitude;
    private Double deliveryLongitude;
    private String promoCode;
    private String notes;
}
