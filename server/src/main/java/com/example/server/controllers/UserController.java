package com.example.server.controllers;

import com.example.server.entities.User;
import com.example.server.services.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

// ═════════════════════════════════════════
// UserController — שכבת HTTP דקה (Thin Controller) לניהול משתמשים.
//
// ארכיטקטורה:
// - Constructor Injection בלבד (Best Practice — לא Field Injection).
// - כל לוגיקה עסקית מואצלת ל-UserService; הבקר רק מתרגם HTTP↔Java.
// - אבטחה: שגיאות פנימיות מתועדות ב-SLF4J, לא נחשפות ללקוח.
// - Stateless: אפס גישה לדיסק בכל הבקר הזה.
// ═════════════════════════════════════════

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    // ── GET /api/users ────────────────────────────────────────────────
    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    // ── PUT /api/users/{id}/toggle ────────────────────────────────────
    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggleAuthorization(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userService.toggleAuthorization(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found"));
        }
    }

    // ── GET /api/users/{username}/rooms ───────────────────────────────
    @GetMapping("/{username}/rooms")
    public List<Integer> getUserRooms(@PathVariable String username) {
        return userService.getUserRooms(username);
    }

    // ── POST /api/users/{username}/rooms/{roomNum} ────────────────────
    @PostMapping("/{username}/rooms/{roomNum}")
    public ResponseEntity<List<Integer>> toggleUserRoom(
            @PathVariable String username,
            @PathVariable int    roomNum) {
        return ResponseEntity.ok(userService.toggleUserRoom(username, roomNum));
    }

    // ── POST /api/users/register ──────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> registerUserAndEnroll(
            @RequestParam("username")                            String          username,
            @RequestParam(value = "fullName", defaultValue = "") String          fullName,
            @RequestParam("samples")                             MultipartFile[] samples) {

        if (samples == null || samples.length < 3) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "נדרשות לפחות שלוש הקלטות קוליות לרישום ביומטרי."));
        }

        try {
            String message = userService.registerAndEnroll(username, fullName, samples);
            return ResponseEntity.ok(Map.of("status", "success", "message", message));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Enrollment failed for user '{}': {}", username, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Enrollment failed. Please try again."));
        }
    }
}
