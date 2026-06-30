package com.example.server.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

// ═══════════════════════════════════════════════════════════════════
// User.java — גרסה סופית מתוקנת
//
//  תיקון קריטי לעומת v1 שהוגש:
//  biometricEmbedding  → nullable = true
//  הסיבה: embedding נכתב רק לאחר enrollment מוצלח דרך FastAPI.
//  ברגע הרישום הראשוני אין embedding — כל save() עם NOT NULL היה
//  מוביל ל-DataIntegrityViolationException גם עם "" ב-DB constraints
//  מסוימים. nullable = true הוא הנכון אדריכלית.
//  (DB column יש לשנות גם: ראה migration_v2.sql)
// ═══════════════════════════════════════════════════════════════════
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "is_authorized", nullable = false)
    private boolean isAuthorized;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * nullable = true — נכתב רק לאחר enrollment מוצלח.
     * Java מחשב ומאחסן את ה-embedding ב-MySQL (ארכיטקטורה Java-centric).
     * FastAPI הוא stateless לחלוטין — אינו מאחסן מידע בין קריאות.
     */
    @JsonIgnore
    @Column(name = "biometric_embedding", nullable = true, columnDefinition = "TEXT")
    private String biometricEmbedding;

    // ── Constructors ────────────────────────────────────────────

    /** חובה ל-JPA */
    public User() {}

    /**
     * Constructor ראשי — ברישום משתמש חדש.
     * biometricEmbedding = null עד שה-FastAPI ירשום ויחזיר embedding.
     */
    public User(String username, String fullName, boolean isAuthorized) {
        this.username           = username;
        this.fullName           = fullName;
        this.isAuthorized       = isAuthorized;
        this.createdAt          = LocalDateTime.now();
        this.biometricEmbedding = null;   // נכתב לאחר /enroll
    }

    // ── Getters ─────────────────────────────────────────────────
    public Long getId()                   { return id; }
    public String getUsername()           { return username; }
    public String getFullName()           { return fullName; }
    public boolean isAuthorized()         { return isAuthorized; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public String getBiometricEmbedding() { return biometricEmbedding; }

    // ── Setters ─────────────────────────────────────────────────
    public void setUsername(String username)         { this.username = username; }
    public void setFullName(String fullName)         { this.fullName = fullName; }
    public void setAuthorized(boolean authorized)    { this.isAuthorized = authorized; }
    public void setBiometricEmbedding(String emb)    { this.biometricEmbedding = emb; }
}