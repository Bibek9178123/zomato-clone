package com.zomato.repository;

import com.zomato.model.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {
    Optional<PromoCode> findByCodeAndIsActiveTrue(String code);
    List<PromoCode> findByIsActiveTrueAndValidUntilBefore(LocalDateTime dateTime);
}
