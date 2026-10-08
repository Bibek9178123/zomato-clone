package com.zomato.service;

import com.zomato.exception.BusinessException;
import com.zomato.model.PromoCode;
import com.zomato.repository.PromoCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromoCodeServiceTest {

    @Mock
    private PromoCodeRepository promoCodeRepository;

    @InjectMocks
    private PromoCodeService promoCodeService;

    private PromoCode validPromo;

    @BeforeEach
    void setUp() {
        validPromo = PromoCode.builder()
                .code("WELCOME50")
                .discountPercentage(BigDecimal.valueOf(20))
                .maxDiscountAmount(BigDecimal.valueOf(100))
                .minOrderAmount(BigDecimal.valueOf(200))
                .usageLimit(10)
                .usedCount(0)
                .isActive(true)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(5))
                .build();
    }

    @Test
    @DisplayName("Successfully apply valid promo code and calculate discount")
    void testApplyPromoCode_Success() {
        when(promoCodeRepository.findByCodeAndIsActiveTrue("WELCOME50"))
                .thenReturn(Optional.of(validPromo));

        BigDecimal orderAmount = BigDecimal.valueOf(300);
        BigDecimal discount = promoCodeService.validateAndApply("WELCOME50", orderAmount);

        // 20% of 300 = 60.00
        assertEquals(0, discount.compareTo(BigDecimal.valueOf(60)));
        assertEquals(1, validPromo.getUsedCount());
        verify(promoCodeRepository, times(1)).save(validPromo);
    }

    @Test
    @DisplayName("Cap discount to maxDiscountAmount")
    void testApplyPromoCode_CapMaxDiscount() {
        when(promoCodeRepository.findByCodeAndIsActiveTrue("WELCOME50"))
                .thenReturn(Optional.of(validPromo));

        BigDecimal orderAmount = BigDecimal.valueOf(1000);
        BigDecimal discount = promoCodeService.validateAndApply("WELCOME50", orderAmount);

        // 20% of 1000 = 200, but maxDiscount is 100
        assertEquals(0, discount.compareTo(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("Throw BusinessException when order amount is less than minOrderAmount")
    void testApplyPromoCode_MinOrderAmountNotMet() {
        when(promoCodeRepository.findByCodeAndIsActiveTrue("WELCOME50"))
                .thenReturn(Optional.of(validPromo));

        BigDecimal orderAmount = BigDecimal.valueOf(150); // min is 200

        assertThrows(BusinessException.class, () ->
                promoCodeService.validateAndApply("WELCOME50", orderAmount));
    }

    @Test
    @DisplayName("Throw BusinessException when promo code is not found")
    void testApplyPromoCode_NotFound() {
        when(promoCodeRepository.findByCodeAndIsActiveTrue("INVALID"))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () ->
                promoCodeService.validateAndApply("INVALID", BigDecimal.valueOf(300)));
    }
}
