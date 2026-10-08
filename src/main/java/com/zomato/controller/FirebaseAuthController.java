package com.zomato.controller;

import com.zomato.dto.response.AuthResponse;
import com.zomato.service.FirebaseAuthService;
import com.zomato.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/firebase")
@RequiredArgsConstructor
public class FirebaseAuthController {

    private final FirebaseAuthService firebaseAuthService;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> loginWithFirebase(@RequestBody Map<String, String> request) {
        String firebaseToken = request.get("token");
        if (firebaseToken == null || firebaseToken.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        String jwt = firebaseAuthService.loginWithFirebase(firebaseToken);
        AuthResponse success = AuthResponse.builder()
            .token(jwt)
            .build();
        return ResponseEntity.ok(success);
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<String> updateFcmToken(@RequestBody Map<String, String> request, 
                                                 @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        String fcmToken = request.get("fcmToken");
        if (userDetails != null && fcmToken != null) {
            userService.updateFcmToken(userDetails.getUsername(), fcmToken);
            return ResponseEntity.ok("FCM Token updated successfully");
        }
        return ResponseEntity.badRequest().body("Invalid request or unauthenticated");
    }
}
