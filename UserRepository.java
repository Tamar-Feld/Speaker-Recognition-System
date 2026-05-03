package com.example.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    // פונקציית קסם: תמצא משתמש לפי השם שלו
    User findByUsername(String username);
}
