package com.zomato.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${app.firebase.service-account-file}")
    private Resource serviceAccountResource;

    @Value("${app.firebase.database-url}")
    private String databaseUrl;

    @Value("${app.firebase.storage-bucket}")
    private String storageBucket;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                if (!serviceAccountResource.exists()) {
                    log.warn("Firebase service account file not found. Firebase will NOT be initialized. To enable Firebase, place your 'firebase-service-account.json' in src/main/resources.");
                    return;
                }
                
                try (InputStream serviceAccount = serviceAccountResource.getInputStream()) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                            .setDatabaseUrl(databaseUrl)
                            .setStorageBucket(storageBucket)
                            .build();

                    FirebaseApp.initializeApp(options);
                    log.info("Firebase has been initialized successfully!");
                }
            }
        } catch (Exception e) {
            log.error("Error initializing Firebase: {}", e.getMessage());
        }
    }
}
