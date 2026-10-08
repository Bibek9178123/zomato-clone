package com.zomato.repository;

import com.zomato.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);
    List<Review> findByCustomerId(Long customerId);
    boolean existsByCustomerIdAndOrderId(Long customerId, Long orderId);
}
