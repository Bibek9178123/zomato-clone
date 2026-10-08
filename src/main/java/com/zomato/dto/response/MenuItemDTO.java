package com.zomato.dto.response;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MenuItemDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private boolean isVeg;
    private boolean isAvailable;
    private String imageUrl;
}
