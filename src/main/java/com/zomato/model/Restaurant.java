package com.zomato.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "restaurants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    private String cuisineType;
    private String address;
    private String phone;
    private String email;
    private Double latitude;
    private Double longitude;

    @Column(name = "is_open")
    @Builder.Default
    private boolean isOpen = true;

    @Column(name = "min_order_amount", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal minOrderAmount = BigDecimal.valueOf(50);

    @Column(name = "avg_delivery_time")
    @Builder.Default
    private Integer avgDeliveryTime = 30;

    @Builder.Default
    private Double rating = 0.0;

    @Builder.Default
    private Integer totalRatings = 0;

    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<MenuItem> menuItems = new ArrayList<>();
}
