package com.zomato.controller;

import com.zomato.dto.request.PromoCodeRequest;
import com.zomato.model.AuditLog;
import com.zomato.model.PromoCode;
import com.zomato.model.User;
import com.zomato.repository.AuditLogRepository;
import com.zomato.repository.OrderRepository;
import com.zomato.repository.RestaurantRepository;
import com.zomato.service.OrderService;
import com.zomato.service.PromoCodeService;
import com.zomato.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService userService;
    private final OrderService orderService;
    private final PromoCodeService promoCodeService;
    private final AuditLogRepository auditLogRepository;
    private final OrderRepository orderRepository;
    private final RestaurantRepository restaurantRepository;

    @GetMapping("/users")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/orders")
    public ResponseEntity<?> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        long ordersToday = orderRepository.findAllByCreatedAtBetween(startOfDay, endOfDay).size();
        long totalRestaurants = restaurantRepository.count();
        long openRestaurants = restaurantRepository.findByIsOpenTrue().size();
        Map<String, Object> stats = new HashMap<>();
        stats.put("ordersToday", ordersToday);
        stats.put("totalRestaurants", totalRestaurants);
        stats.put("openRestaurants", openRestaurants);
        stats.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/promo-codes")
    public ResponseEntity<PromoCode> createPromoCode(@RequestBody PromoCodeRequest request) {
        return ResponseEntity.ok(promoCodeService.createPromoCode(request));
    }

    @GetMapping("/promo-codes")
    public ResponseEntity<List<PromoCode>> getAllPromoCodes() {
        return ResponseEntity.ok(promoCodeService.getAllActiveCodes());
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLog>> getAuditLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId) {
        if (entityType != null && entityId != null) {
            return ResponseEntity.ok(auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId));
        }
        return ResponseEntity.ok(auditLogRepository.findAll());
    }
}
