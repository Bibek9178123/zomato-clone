package com.zomato.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class MenuItemRequest {
    @NotBlank private String name;
    private String description;
    @NotNull private BigDecimal price;
    private String category;
    private boolean veg;
    private String imageUrl;
}
