package com.zomato.controller;

import com.zomato.dto.request.PlaceOrderRequest;
import com.zomato.dto.response.OrderResponse;
import com.zomato.model.enums.OrderStatus;
import com.zomato.repository.UserRepository;
import com.zomato.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final UserRepository userRepository;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            Authentication authentication) {
        Long customerId = getUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeOrder(request, customerId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<List<OrderResponse>> getMyOrders(Authentication authentication) {
        Long customerId = getUserId(authentication);
        return ResponseEntity.ok(orderService.getCustomerOrders(customerId));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable Long id,
            @RequestParam OrderStatus status,
            Authentication authentication) {
        return ResponseEntity.ok(orderService.updateOrderStatus(id, status, authentication.getName()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(orderService.cancelOrder(id, getUserId(authentication)));
    }

    @GetMapping("/restaurant/{restaurantId}")
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    public ResponseEntity<List<OrderResponse>> getRestaurantOrders(@PathVariable Long restaurantId) {
        return ResponseEntity.ok(orderService.getRestaurantOrders(restaurantId));
    }

    private Long getUserId(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName()).orElseThrow().getId();
    }
}
