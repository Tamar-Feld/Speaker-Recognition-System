package com.example.server;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

@Entity
@Table(name = "users") // שם הטבלה ב-SQL
public class User {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private String username; // השם שפייתון מחזיר (למשל: tamar)
    private boolean isAuthorized; // האם מותר לה להיכנס?
    // בנאי ריק (חובה ל-Hibernate)
    public User() {}

    public User(String username, boolean isAuthorized) {
        this.username = username;
        this.isAuthorized = isAuthorized;
    }
    // --- Getters ---
    public String getUsername() { return username; }
    public boolean isAuthorized() { return isAuthorized; }

    // --- Setters (קריטי לניהול!) ---
    public void setAuthorized(boolean authorized) {
        this.isAuthorized = authorized;
    }

    public void setUsername(String username) {
        this.username = username;
    }

}