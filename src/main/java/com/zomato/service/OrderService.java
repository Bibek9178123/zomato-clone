package com.zomato.service;

import com.zomato.dto.request.OrderItemRequest;
import com.zomato.dto.request.PlaceOrderRequest;
import com.zomato.dto.response.OrderItemResponse;
import com.zomato.dto.response.OrderResponse;
import com.zomato.exception.BusinessException;
import com.zomato.exception.ResourceNotFoundException;
import com.zomato.model.*;
import com.zomato.model.enums.OrderStatus;
import com.zomato.model.enums.PaymentStatus;
import com.zomato.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final PromoCodeService promoCodeService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FirebaseNotificationService firebaseNotificationService;

    // In-process per-restaurant lock map (prevents concurrent order conflicts within the same JVM)
    private final ConcurrentHashMap<Long, ReentrantLock> restaurantLocks = new ConcurrentHashMap<>();

    private ReentrantLock getRestaurantLock(Long restaurantId) {
        return restaurantLocks.computeIfAbsent(restaurantId, id -> new ReentrantLock());
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request, Long customerId) {
        ReentrantLock lock = getRestaurantLock(request.getRestaurantId());
        try {
            boolean acquired = lock.tryLock();
            if (!acquired) {
                throw new BusinessException("Restaurant is too busy. Please try again.");
            }

            User customer = userRepository.findById(customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
            Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

            if (!restaurant.isOpen()) {
                throw new BusinessException("Restaurant is currently closed");
            }

            Order order = new Order();
            order.setOrderNumber(generateOrderNumber());
            order.setCustomer(customer);
            order.setRestaurant(restaurant);
            order.setStatus(OrderStatus.PENDING);
            order.setDeliveryAddress(request.getDeliveryAddress());
            order.setDeliveryLatitude(request.getDeliveryLatitude());
            order.setDeliveryLongitude(request.getDeliveryLongitude());
            order.setNotes(request.getNotes());

            BigDecimal totalAmount = BigDecimal.ZERO;
            List<OrderItem> orderItems = new ArrayList<>();

            for (OrderItemRequest itemRequest : request.getItems()) {
                MenuItem menuItem = menuItemRepository.findById(itemRequest.getMenuItemId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Menu item not found: " + itemRequest.getMenuItemId()));

                if (!menuItem.isAvailable()) {
                    throw new BusinessException("Menu item not available: " + menuItem.getName());
                }
                if (!menuItem.getRestaurant().getId().equals(request.getRestaurantId())) {
                    throw new BusinessException("Menu item does not belong to this restaurant");
                }

                OrderItem orderItem = new OrderItem();
                orderItem.setMenuItem(menuItem);
                orderItem.setQuantity(itemRequest.getQuantity());
                orderItem.setUnitPrice(menuItem.getPrice());
                orderItem.setSubtotal(menuItem.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
                orderItem.setOrder(order);
                orderItems.add(orderItem);
                totalAmount = totalAmount.add(orderItem.getSubtotal());
            }

            order.setOrderItems(orderItems);

            if (totalAmount.compareTo(restaurant.getMinOrderAmount()) < 0) {
                throw new BusinessException("Minimum order amount is " + restaurant.getMinOrderAmount());
            }

            BigDecimal discountAmount = BigDecimal.ZERO;
            if (request.getPromoCode() != null && !request.getPromoCode().isEmpty()) {
                discountAmount = promoCodeService.validateAndApply(request.getPromoCode(), totalAmount);
                order.setPromoCode(request.getPromoCode());
                order.setDiscountAmount(discountAmount);
            }

            order.setTotalAmount(totalAmount.subtract(discountAmount));
            order.setEstimatedDeliveryTime(
                    LocalDateTime.now().plusMinutes(restaurant.getAvgDeliveryTime()));

            Order savedOrder = orderRepository.save(order);

            auditService.log("ORDER", savedOrder.getId(), "PLACED",
                    customer.getEmail(), "Order placed for restaurant: " + restaurant.getName(), "");

            log.info("Order {} placed successfully by customer {}",
                    savedOrder.getOrderNumber(), customerId);

            messagingTemplate.convertAndSend(
                    "/topic/restaurant/" + restaurant.getId() + "/orders",
                    savedOrder.getOrderNumber());

            return mapToOrderResponse(savedOrder);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus, String performedBy) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        validateStatusTransition(order.getStatus(), newStatus);
        order.setStatus(newStatus);
        if (newStatus == OrderStatus.DELIVERED) {
            order.setDeliveredAt(LocalDateTime.now());
        }
        Order updated = orderRepository.save(order);
        messagingTemplate.convertAndSend("/topic/order/" + orderId + "/status", newStatus.name());
        
        // Push notification logic
        if (order.getCustomer().getFcmToken() != null && !order.getCustomer().getFcmToken().isEmpty()) {
            String title = "Order Update";
            String body = "Your order " + order.getOrderNumber() + " is now " + newStatus.name();
            firebaseNotificationService.sendNotification(order.getCustomer().getFcmToken(), title, body, Map.of("orderId", String.valueOf(orderId)));
        }
        
        auditService.log("ORDER", orderId, "STATUS_UPDATED", performedBy,
                "Status changed to: " + newStatus, "");
        log.info("Order {} status updated to {} by {}",
                order.getOrderNumber(), newStatus, performedBy);
        return mapToOrderResponse(updated);
    }

    public OrderResponse getOrderById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return mapToOrderResponse(order);
    }

    public List<OrderResponse> getCustomerOrders(Long customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream().map(this::mapToOrderResponse).toList();
    }

    public List<OrderResponse> getRestaurantOrders(Long restaurantId) {
        return orderRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId)
                .stream().map(this::mapToOrderResponse).toList();
    }

    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream().map(this::mapToOrderResponse).toList();
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("You can only cancel your own orders");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new BusinessException("Order cannot be cancelled at this stage");
        }
        order.setStatus(OrderStatus.CANCELLED);
        Order cancelled = orderRepository.save(order);
        if (order.getPayment() != null && order.getPayment().getStatus() == PaymentStatus.COMPLETED) {
            paymentService.initiateRefund(order.getPayment().getId());
        }
        auditService.log("ORDER", orderId, "CANCELLED",
                order.getCustomer().getEmail(), "Customer cancelled order", "");
        return mapToOrderResponse(cancelled);
    }

    private void validateStatusTransition(OrderStatus current, OrderStatus next) {
        Map<OrderStatus, Set<OrderStatus>> transitions = Map.of(
                OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
                OrderStatus.CONFIRMED, Set.of(OrderStatus.PREPARING, OrderStatus.CANCELLED),
                OrderStatus.PREPARING, Set.of(OrderStatus.OUT_FOR_DELIVERY),
                OrderStatus.OUT_FOR_DELIVERY, Set.of(OrderStatus.DELIVERED),
                OrderStatus.DELIVERED, Set.of(),
                OrderStatus.CANCELLED, Set.of(),
                OrderStatus.REFUNDED, Set.of()
        );
        if (!transitions.getOrDefault(current, Set.of()).contains(next)) {
            throw new BusinessException(
                    "Invalid status transition from " + current + " to " + next);
        }
    }

    private String generateOrderNumber() {
        return "ZMT-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private OrderResponse mapToOrderResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setStatus(order.getStatus());
        response.setTotalAmount(order.getTotalAmount());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setDeliveryAddress(order.getDeliveryAddress());
        response.setEstimatedDeliveryTime(order.getEstimatedDeliveryTime());
        response.setDeliveredAt(order.getDeliveredAt());
        response.setPromoCode(order.getPromoCode());
        response.setNotes(order.getNotes());
        response.setCreatedAt(order.getCreatedAt());
        if (order.getOrderItems() != null) {
            response.setItems(order.getOrderItems().stream().map(item -> {
                OrderItemResponse itemResponse = new OrderItemResponse();
                itemResponse.setMenuItemId(item.getMenuItem().getId());
                itemResponse.setMenuItemName(item.getMenuItem().getName());
                itemResponse.setQuantity(item.getQuantity());
                itemResponse.setUnitPrice(item.getUnitPrice());
                itemResponse.setSubtotal(item.getSubtotal());
                return itemResponse;
            }).toList());
        }
        return response;
    }
}
