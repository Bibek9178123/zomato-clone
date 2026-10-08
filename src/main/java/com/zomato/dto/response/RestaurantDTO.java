package com.zomato.dto.response;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RestaurantDTO {
    private Long id;
    private String name;
    private String description;
    private String cuisineType;
    private String address;
    private Double rating;
    private boolean isOpen;
    private Integer avgDeliveryTime;
    private BigDecimal minOrderAmount;
    private String imageUrl;
    private Double latitude;
    private Double longitude;
}
