package com.example.server.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

// ═══════════════════════════════════════════════════════════════════
// Admin.java — ישות JPA אמיתית.
//
// תפקיד: ייצוג טבלת admins במסד הנתונים הסטנדרטי.
// הערות ארכיטקטורה:
// - סיסמה: גיבוב Bcrypt חד-כיווני בלבד. מוסתרת ב-JSON באמצעות @JsonIgnore.
// - אבטחה: השדה username חסר Setter על מנת למנוע שינוי זהות לאחר היצירה.
// - מעקב: מכיל lastLogin שעודכן דרך ה-Service בכל התחברות מוצלחת.
// ═══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "admins")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    /**
     * Bcrypt hash בלבד (כ-60 תווים, לדוגמה $2a$10$...).
     * @JsonIgnore — מונע חשיפה (Serialization) לדפדפן (React).
     */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * מתעד את זמן ההתחברות האחרון (מוחלף בכל Login מוצלח).
     * תואם לאפיון התכנוני בספר הפרויקט.
     */
    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    /** Constructor חובה ל-JPA (Hibernate Reflection) */
    public Admin() {}

    /** Constructor ליצירת מנהל חדש דרך ה-Bootstrap */
    public Admin(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    // ── Getters ─────────────────────────────────────────────────
    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    // ── Setters ─────────────────────────────────────────────────שתמש ב-@Value("${jwt.secret}") כדי למשוך את המפתח הסודי מקובץ ה-application.properties
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }
}