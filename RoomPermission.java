package com.example.server;

import jakarta.persistence.*;

@Entity
@Table(name = "room_permissions")
public class RoomPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;   // שם המשתמש (למשל: tamar)
    private int roomNumber;    // מספר החדר (1-6)

    // בנאי ריק (חובה)
    public RoomPermission() {}

    // בנאי לשימוש שלנו
    public RoomPermission(String username, int roomNumber) {
        this.username = username;
        this.roomNumber = roomNumber;
    }

    // Getters
    public String getUsername() { return username; }
    public int getRoomNumber() { return roomNumber; }
}