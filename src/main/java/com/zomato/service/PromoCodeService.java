package com.zomato.service;

import com.zomato.dto.request.PromoCodeRequest;
import com.zomato.exception.BusinessException;
import com.zomato.model.PromoCode;
import com.zomato.repository.PromoCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoCodeService {

    private final PromoCodeRepository promoCodeRepository;

    @Transactional
    public BigDecimal validateAndApply(String code, BigDecimal orderAmount) {
        PromoCode promoCode = promoCodeRepository.findByCodeAndIsActiveTrue(code)
                .orElseThrow(() -> new BusinessException("Invalid or expired promo code: " + code));

        // Validate date range
        LocalDateTime now = LocalDateTime.now();
        if (promoCode.getValidFrom() != null && now.isBefore(promoCode.getValidFrom())) {
            throw new BusinessException("Promo code is not yet active");
        }
        if (promoCode.getValidUntil() != null && now.isAfter(promoCode.getValidUntil())) {
            throw new BusinessException("Promo code has expired");
        }

        // Validate minimum order amount
        if (promoCode.getMinOrderAmount() != null &&
                orderAmount.compareTo(promoCode.getMinOrderAmount()) < 0) {
            throw new BusinessException("Minimum order amount of " +
                    promoCode.getMinOrderAmount() + " required for this promo code");
        }

        // Validate usage limit
        if (promoCode.getUsageLimit() != null &&
                promoCode.getUsedCount() >= promoCode.getUsageLimit()) {
            throw new BusinessException("Promo code usage limit reached");
        }

        // Calculate discount
        BigDecimal discount = orderAmount
                .multiply(promoCode.getDiscountPercentage())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Apply max discount cap
        if (promoCode.getMaxDiscountAmount() != null &&
                discount.compareTo(promoCode.getMaxDiscountAmount()) > 0) {
            discount = promoCode.getMaxDiscountAmount();
        }

        // Increment usage count
        promoCode.setUsedCount(promoCode.getUsedCount() + 1);
        promoCodeRepository.save(promoCode);

        log.info("Promo code {} applied. Discount: {}", code, discount);
        return discount;
    }

    @Transactional
    public void expirePromoCodes() {
        List<PromoCode> expiredCodes = promoCodeRepository
                .findByIsActiveTrueAndValidUntilBefore(LocalDateTime.now());
        expiredCodes.forEach(code -> {
            code.setActive(false);
            log.info("Expired promo code: {}", code.getCode());
        });
        promoCodeRepository.saveAll(expiredCodes);
        log.info("Expired {} promo codes", expiredCodes.size());
    }

    @Transactional
    public PromoCode createPromoCode(PromoCodeRequest request) {
        if (promoCodeRepository.findByCodeAndIsActiveTrue(request.getCode()).isPresent()) {
            throw new BusinessException("Promo code already exists: " + request.getCode());
        }
        PromoCode promoCode = PromoCode.builder()
                .code(request.getCode().toUpperCase())
                .description(request.getDescription())
                .discountPercentage(request.getDiscountPercentage())
                .maxDiscountAmount(request.getMaxDiscountAmount())
                .minOrderAmount(request.getMinOrderAmount())
                .validFrom(request.getValidFrom())
                .validUntil(request.getValidUntil())
                .usageLimit(request.getUsageLimit())
                .isActive(true)
                .usedCount(0)
                .build();
        return promoCodeRepository.save(promoCode);
    }

    public List<PromoCode> getAllActiveCodes() {
        return promoCodeRepository.findAll().stream()
                .filter(PromoCode::isActive)
                .toList();
    }
}
