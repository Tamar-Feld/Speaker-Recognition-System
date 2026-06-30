package com.example.server.repositories;

import com.example.server.entities.RoomPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RoomPermissionRepository extends JpaRepository<RoomPermission, Long> {

    // קיים — בדיקת קיום הרשאה ספציפית
    boolean existsByUsernameAndRoomNumber(String username, int roomNumber);

    // חדש — שליפת כל החדרים של משתמש
    List<RoomPermission> findByUsername(String username);

    // חדש — מחיקת הרשאה ספציפית (חייב @Transactional לפעולת מחיקה derived)
    @Transactional
    void deleteByUsernameAndRoomNumber(String username, int roomNumber);
}