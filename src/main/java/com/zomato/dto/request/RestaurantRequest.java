package com.zomato.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RestaurantRequest {
    @NotBlank private String name;
    private String description;
    private String cuisineType;
    private String address;
    private String phone;
    private String email;
    private Double latitude;
    private Double longitude;
    private BigDecimal minOrderAmount;
    private Integer avgDeliveryTime;
}
