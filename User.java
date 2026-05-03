package com.example.server;
import jakarta.persistence.*;

@Entity
@Table(name = "users") // שם הטבלה ב-SQL
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false)
    private String username;
    private boolean isAuthorized; // האם מותר לה להיכנס?
    // בנאי ריק (חובה ל-Hibernate)
    public User() {}

    public User(String username, boolean isAuthorized) {
        this.username = username;
        this.isAuthorized = isAuthorized;
    }
    // --- Getters ---
    public Long getId() { return id; }
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