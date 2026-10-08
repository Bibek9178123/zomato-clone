package com.zomato.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.zomato.exception.BusinessException;
import com.zomato.model.User;
import com.zomato.model.enums.UserRole;
import com.zomato.repository.UserRepository;
import com.zomato.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FirebaseAuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public String loginWithFirebase(String firebaseIdToken) {
        try {
            // Verify the ID token using Firebase Admin SDK
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(firebaseIdToken);
            String uid = decodedToken.getUid();
            String email = decodedToken.getEmail();
            String phone = (String) decodedToken.getClaims().get("phone_number");

            if (email == null && phone == null) {
                throw new BusinessException("Firebase token must contain an email or phone number");
            }

            // Find existing user by Firebase UID, or fallback to Email/Phone
            Optional<User> userOptional = userRepository.findByFirebaseUid(uid);
            
            if (userOptional.isEmpty() && email != null) {
                userOptional = userRepository.findByEmail(email);
            }

            User user;
            if (userOptional.isPresent()) {
                user = userOptional.get();
                // Update missing UID if we matched by email/phone from a legacy account
                if (user.getFirebaseUid() == null) {
                    user.setFirebaseUid(uid);
                    userRepository.save(user);
                }
            } else {
                // Register new user automatically
                user = User.builder()
                        .firebaseUid(uid)
                        .email(email != null ? email : uid + "@firebase.placeholder.com")
                        .phone(phone)
                        .name(decodedToken.getName() != null ? decodedToken.getName() : "Firebase User")
                        .password(UUID.randomUUID().toString()) // Dummy password, auth is handled by Firebase
                        .role(UserRole.CUSTOMER)
                        .active(true)
                        .build();
                user = userRepository.save(user);
                log.info("Registered new user via Firebase Auth: {}", user.getEmail());
            }

            // Generate our own JWT token for the Zomato clone ecosystem
            org.springframework.security.core.userdetails.UserDetails userDetails = 
                org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities(user.getRole().name())
                .build();
            return jwtTokenProvider.generateToken(userDetails);

        } catch (Exception e) {
            log.error("Firebase authentication failed: {}", e.getMessage());
            throw new BusinessException("Invalid Firebase token: " + e.getMessage());
        }
    }
}
