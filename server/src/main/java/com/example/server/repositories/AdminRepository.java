package com.example.server.repositories;

import com.example.server.entities.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

// ═══════════════════════════════════════════════════════════════════
// AdminRepository.java — שכבת הגישה לנתונים (Data Access Layer).
//
// תפקיד: ניהול שאילתות מול טבלת admins במסד הנתונים.
// הערות ארכיטקטורה:
// - שימוש ב-Optional למניעת שגיאות NullPointerException (NPE)
//   במקרים בהם מוזן שם משתמש שאינו קיים במערכת בעת ניסיון התחברות.
// ═══════════════════════════════════════════════════════════════════

public interface AdminRepository extends JpaRepository<Admin, Long> {

    /**
     * שליפת מנהל לפי שם משתמש (Business Key).
     * @param username שם המשתמש שהוזן בבקשת ההתחברות.
     * @return Optional המכיל את המנהל אם נמצא, או Optional ריק אם לא קיים.
     */
    Optional<Admin> findByUsername(String username);
}