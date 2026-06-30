package com.example.server.repositories;

import com.example.server.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // פונקציית קסם: תמצא משתמש לפי השם שלו
    Optional<User> findByUsername(String username);
}
