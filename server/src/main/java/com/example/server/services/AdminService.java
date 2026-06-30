package com.example.server.services;

import com.example.server.config.JwtUtil;
import com.example.server.entities.Admin;
import com.example.server.repositories.AdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// ═══════════════════════════════════════════════════════════════════
// AdminService — אימות וניהול חשבונות מנהל.
//
// ארכיטקטורה:
// - סיסמאות נשמרות אך ורק כ-Bcrypt hash (לעולם לא טקסט גלוי).
// - המחלקה מותאמת לעבודה מול Optional (כפי שמוגדר ב-Repository).
// - תמיכה ב-Stateless JWT: לאחר אימות מוצלח של ה-Bcrypt,
//   השירות מנפיק אסימון JWT החתום דיגיטלית עבור ה-Client.
// - ניהול טרנזקציות: כל פעולה מעדכנת DB נעטפת ב-@Transactional.
// ═══════════════════════════════════════════════════════════════════

@Service
public class AdminService {

    private static final Logger logger = LoggerFactory.getLogger(AdminService.class);

    private final AdminRepository adminRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor Injection — מזריק את ה-PasswordEncoder Bean מ-SecurityConfig.
     * אסור ליצור new BCryptPasswordEncoder() ישירות — זה עוקף את ה-Spring DI Container.
     */
    @Autowired
    public AdminService(AdminRepository adminRepository, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * אימות מנהל והנפקת JWT.
     * @param username שם משתמש גלוי
     * @param rawPassword סיסמה גלויה (מה-JSON של הבקשה)
     * @return אסימון JWT חתום
     * @throws BadCredentialsException אם שם המשתמש לא קיים או הסיסמה שגויה
     */
    @Transactional
    public String authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            throw new BadCredentialsException("Missing credentials");
        }

        // 1. שליפת המשתמש תוך שימוש ב-Optional (הגנה מ-NPE)
        Admin admin = adminRepository.findByUsername(username)
                .orElseThrow(() -> {
                    logger.warn("Login failed — username '{}' not found in database.", username);
                    return new BadCredentialsException("Invalid username or password");
                });

        // 2. השוואת Bcrypt
        if (!passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            logger.warn("Login failed — password mismatch for username '{}'. " +
                    "Hash in DB starts with: '{}'",
                    username,
                    admin.getPasswordHash() != null ? admin.getPasswordHash().substring(0, Math.min(7, admin.getPasswordHash().length())) : "NULL");
            throw new BadCredentialsException("Invalid username or password");
        }

        // 3. עדכון זמן התחברות אחרון (lastLogin)
        admin.setLastLogin(LocalDateTime.now());
        adminRepository.save(admin);

        // 4. יצירת והחזרת JWT Token
        return jwtUtil.generateToken(admin.getUsername());
    }

    /**
     * יצירת מנהל חדש (לשימוש מכלי Bootstrap או סקריפט ראשוני).
     */
    @Transactional
    public Admin createAdmin(String username, String rawPassword) {
        if (adminRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Admin '" + username + "' already exists");
        }
        String hash = passwordEncoder.encode(rawPassword);
        return adminRepository.save(new Admin(username, hash));
    }

    /**
     * איפוס סיסמה = דריסת ה-hash הישן בחדש.
     */
    @Transactional
    public void resetPassword(String username, String newRawPassword) {
        Admin admin = adminRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin '" + username + "' not found"));

        admin.setPasswordHash(passwordEncoder.encode(newRawPassword));
        adminRepository.save(admin);
    }
}