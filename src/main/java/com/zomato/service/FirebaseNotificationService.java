package com.zomato.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class FirebaseNotificationService {

    @Async
    public void sendNotification(String targetToken, String title, String body, Map<String, String> data) {
        if (targetToken == null || targetToken.isEmpty()) {
            log.warn("Target FCM token is null or empty. Cannot send notification.");
            return;
        }

        try {
            Message.Builder messageBuilder = Message.builder()
                    .setToken(targetToken)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());
                            
            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            Message message = messageBuilder.build();
            String response = FirebaseMessaging.getInstance().send(message);
            log.info("Successfully sent FCM message: {}", response);
        } catch (Exception e) {
            log.error("Failed to send FCM message to token {}: {}", targetToken, e.getMessage());
        }
    }
}
