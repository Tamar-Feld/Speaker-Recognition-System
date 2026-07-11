package com.example.server.controllers;

import com.example.server.services.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

// ═══════════════════════════════════════════════════════════════════
// AdminController — בקר אימות וניהול גישה למנהלים (API Endpoint).
//
// ארכיטקטורה:
// - הבקר "רזה" (Thin Controller): מטפל רק בפרוטוקול HTTP והמרת JSON.
// - אבטחה: הנתיב /login מנפיק טוקן JWT בעת התחברות מוצלחת.
// - DTOs: שימוש ב-Java Records לייצוג מובנה ובטוח של ה-JSON הנכנס והיוצא
//   (במקום שימוש ב-Map גנרי חסר Type-Safety).
// - הזרקת תלויות: מבוצעת דרך הבנאי (Constructor Injection).
// ═══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    /**
     * Constructor Injection (תקן התעשייה)
     */
    @Autowired
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ── DTOs (Data Transfer Objects) ──────────────────────────────
    // שימוש ב-records ליצירת אובייקטים קלי-משקל ובטוחים להעברת נתונים.

    public record LoginRequest(String username, String password) {}
    public record AuthResponse(boolean success, String token, String message) {}
    public record ResetPasswordRequest(String username, String newPassword) {}
    public record BasicResponse(boolean success, String message) {}

    // ── Endpoints ──────────────────────────────────────────────────

    /**
     * התחברות מנהל.
     * מקבל שם משתמש וסיסמה, מחזיר JWT במקרה של הצלחה.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        try {
            // קריאה ל-Service המאובטח שלנו (שמשווה Bcrypt ומייצר JWT)
            String jwtToken = adminService.authenticate(request.username(), request.password());

            // החזרת קוד 200 (OK) עם הטוקן ללקוח ה-React
            return ResponseEntity.ok(new AuthResponse(true, jwtToken, "התחברות בוצעה בהצלחה"));

        } catch (BadCredentialsException e) {
            // טיפול אלגנטי בשגיאת אימות, החזרת קוד 401 (Unauthorized) בלי לחשוף שגיאות פנימיות
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(false, null, "שם משתמש או סיסמה שגויים"));
        }
    }

    /**
     * איפוס סיסמה למנהל קיים.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<BasicResponse> resetPassword(
            @RequestBody ResetPasswordRequest request,
            java.security.Principal principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new BasicResponse(false, "יש להתחבר לפני איפוס סיסמה"));
        }
        try {
            // שם המשתמש נלקח מה-JWT המאומת, לא ממה שהלקוח שלח בגוף הבקשה —
            // כך אי אפשר לאפס סיסמה של מנהל אחר, גם אם משנים את שדה ה-username בבקשה
            adminService.resetPassword(principal.getName(), request.newPassword());
            return ResponseEntity.ok(new BasicResponse(true, "הסיסמה אופסה בהצלחה"));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new BasicResponse(false, e.getMessage()));
        }
    }
    }
