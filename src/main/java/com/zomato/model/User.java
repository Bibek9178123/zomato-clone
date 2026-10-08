package com.zomato.model;

import com.zomato.model.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "phone")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private UserRole role;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "firebase_uid", unique = true)
    private String firebaseUid;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "active")
    @Builder.Default
    private boolean active = true;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
