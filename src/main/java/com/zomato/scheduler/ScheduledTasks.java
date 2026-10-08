package com.zomato.scheduler;

import com.zomato.model.Order;
import com.zomato.model.enums.OrderStatus;
import com.zomato.repository.OrderRepository;
import com.zomato.service.AuditService;
import com.zomato.service.PromoCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTasks {

    private final PromoCodeService promoCodeService;
    private final OrderRepository orderRepository;
    private final AuditService auditService;

    /**
     * Expire promo codes daily at midnight.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void expirePromoCodes() {
        log.info("[SCHEDULER] Running promo code expiration job at: {}", LocalDateTime.now());
        promoCodeService.expirePromoCodes();
    }

    /**
     * Generate hourly order report.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void generateHourlyReport() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        LocalDateTime now = LocalDateTime.now();
        List<Order> recentOrders = orderRepository.findAllByCreatedAtBetween(oneHourAgo, now);
        log.info("[SCHEDULER] Hourly report: {} orders in the last hour", recentOrders.size());
    }

    /**
     * Check for stuck PENDING orders every 5 minutes.
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void checkStuckOrders() {
        LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING);
        pendingOrders.stream()
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isBefore(thirtyMinutesAgo))
                .forEach(o -> log.warn(
                        "[SCHEDULER] Order {} has been PENDING for more than 30 minutes",
                        o.getOrderNumber()));
    }

    /**
     * Daily revenue report at 11 PM.
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void generateDailyRevenueReport() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1).minusSeconds(1);
        List<Order> todaysOrders = orderRepository.findAllByCreatedAtBetween(startOfDay, endOfDay);
        log.info("[SCHEDULER] Daily Revenue Report - Total orders today: {}", todaysOrders.size());
        auditService.log("REPORT", null, "DAILY_REVENUE_REPORT", "SYSTEM",
                "Daily orders: " + todaysOrders.size(), "");
    }
}
