package com.zomato.repository;

import com.zomato.model.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    List<Restaurant> findByOwnerId(Long ownerId);
    List<Restaurant> findByIsOpenTrue();
    List<Restaurant> findByCuisineTypeContainingIgnoreCase(String cuisineType);

    @Query(value = "SELECT * FROM restaurants r WHERE " +
            "r.latitude IS NOT NULL AND r.longitude IS NOT NULL AND " +
            "(6371 * acos(LEAST(1.0, GREATEST(-1.0, " +
            "cos(radians(:lat)) * cos(radians(r.latitude)) * " +
            "cos(radians(r.longitude) - radians(:lng)) + " +
            "sin(radians(:lat)) * sin(radians(r.latitude)))))) < :radiusKm " +
            "ORDER BY (6371 * acos(LEAST(1.0, GREATEST(-1.0, " +
            "cos(radians(:lat)) * cos(radians(r.latitude)) * " +
            "cos(radians(r.longitude) - radians(:lng)) + " +
            "sin(radians(:lat)) * sin(radians(r.latitude)))))) ASC",
            nativeQuery = true)
    List<Restaurant> findRestaurantsNearby(@Param("lat") double lat,
                                           @Param("lng") double lng,
                                           @Param("radiusKm") double radiusKm);
}
