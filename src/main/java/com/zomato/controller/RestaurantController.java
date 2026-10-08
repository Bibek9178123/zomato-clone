package com.zomato.controller;

import com.zomato.dto.request.MenuItemRequest;
import com.zomato.dto.request.RestaurantRequest;
import com.zomato.dto.response.MenuItemDTO;
import com.zomato.dto.response.RestaurantDTO;
import com.zomato.repository.UserRepository;
import com.zomato.service.RestaurantService;
import com.zomato.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;
    private final SearchService searchService;
    private final UserRepository userRepository;

    // ---- Public endpoints ----

    @GetMapping("/api/restaurants/public/all")
    public ResponseEntity<List<RestaurantDTO>> getAllRestaurants() {
        return ResponseEntity.ok(restaurantService.getAllRestaurants());
    }

    @GetMapping("/api/restaurants/public/{id}")
    public ResponseEntity<RestaurantDTO> getRestaurant(@PathVariable Long id) {
        return ResponseEntity.ok(restaurantService.getRestaurantById(id));
    }

    @GetMapping("/api/restaurants/public/{id}/menu")
    public ResponseEntity<List<MenuItemDTO>> getMenu(@PathVariable Long id) {
        return ResponseEntity.ok(restaurantService.getMenuByRestaurantId(id));
    }

    @GetMapping("/api/restaurants/public/nearby")
    public ResponseEntity<List<RestaurantDTO>> getNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5.0") double radius) {
        return ResponseEntity.ok(restaurantService.getNearbyRestaurants(lat, lng, radius));
    }

    @GetMapping("/api/restaurants/public/search")
    public ResponseEntity<?> search(@RequestParam String q) {
        return ResponseEntity.ok(searchService.searchRestaurants(q));
    }

    // ---- Restaurant Owner endpoints ----

    @PostMapping("/api/restaurant-owner/restaurants")
    public ResponseEntity<RestaurantDTO> createRestaurant(
            @Valid @RequestBody RestaurantRequest request,
            Authentication authentication) {
        Long ownerId = userRepository.findByEmail(authentication.getName()).orElseThrow().getId();
        return ResponseEntity.ok(restaurantService.createRestaurant(request, ownerId));
    }

    @PutMapping("/api/restaurant-owner/restaurants/{id}")
    public ResponseEntity<RestaurantDTO> updateRestaurant(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantRequest request) {
        return ResponseEntity.ok(restaurantService.updateRestaurant(id, request));
    }

    @PostMapping("/api/restaurant-owner/restaurants/{id}/menu")
    public ResponseEntity<MenuItemDTO> addMenuItem(
            @PathVariable Long id,
            @Valid @RequestBody MenuItemRequest request) {
        return ResponseEntity.ok(restaurantService.addMenuItem(id, request));
    }

    @PutMapping("/api/restaurant-owner/menu/{id}")
    public ResponseEntity<MenuItemDTO> updateMenuItem(
            @PathVariable Long id,
            @RequestBody MenuItemRequest request) {
        return ResponseEntity.ok(restaurantService.updateMenuItem(id, request));
    }

    @DeleteMapping("/api/restaurant-owner/menu/{id}")
    public ResponseEntity<Void> deleteMenuItem(@PathVariable Long id) {
        restaurantService.deleteMenuItem(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/restaurant-owner/restaurants/{id}/toggle")
    public ResponseEntity<RestaurantDTO> toggleStatus(@PathVariable Long id) {
        return ResponseEntity.ok(restaurantService.toggleRestaurantStatus(id));
    }
}
