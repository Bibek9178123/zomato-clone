package com.zomato.repository;

import com.zomato.model.User;
import com.zomato.model.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByFirebaseUid(String firebaseUid);
    boolean existsByEmail(String email);
    List<User> findByRole(UserRole role);
}
