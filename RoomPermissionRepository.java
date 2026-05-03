package com.example.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomPermissionRepository extends JpaRepository<RoomPermission, Long> {

    // פונקציית הקסם: בודקת האם קיים אישור ספציפי למשתמש בחדר ספציפי
    boolean existsByUsernameAndRoomNumber(String username, int roomNumber);
}
