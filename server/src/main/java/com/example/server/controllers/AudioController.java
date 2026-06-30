package com.example.server.controllers;

import com.example.server.services.AudioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// ═══════════════════════════════════════════════════════════════════
// AudioController — שכבת ה-HTTP (Thin Controller) לבקשות אודיו.
//
// ארכיטקטורה ונקודות לציון:
// - הזרקת תלויות: Constructor Injection.
// - אבטחת מידע: שגיאות פנימיות מתועדות בלוג השרת (SLF4J) ולא
//   נחשפות ישירות ללקוח כדי למנוע Information Disclosure.
// - ולידציה: סינון קבצים ריקים לפני פנייה לשכבה העסקית.
// - Type Safety: שימוש ב-record במקום Map לבניית אובייקט השגיאה.
// ═══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/api/audio")
@CrossOrigin(origins = "*")
public class AudioController {

    // שימוש במערכת הלוגים התקנית של Spring במקום e.printStackTrace()
    private static final Logger logger = LoggerFactory.getLogger(AudioController.class);

    private final AudioService audioService;

    /**
     * Constructor Injection
     */
    @Autowired
    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    // ── DTO ──────────────────────────────────────────────────────────
    // מחלקה פנימית בטוחה (Type-Safe) המחליפה את השימוש ב-Map גנרי חסר סוג
    public record AccessResponse(boolean accessGranted, String identifiedSpeaker, double confidence, String message) {}

    // ── Endpoints ──────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("roomId") int roomId) {

        // 1. ולידציה בסיסית של הקלט (Guard Clause)
        if (file.isEmpty()) {
            logger.warn("Received empty audio file for room {}", roomId);
            return ResponseEntity.badRequest()
                    .body(new AccessResponse(false, "unknown", 0.0, "⛔ Error: No audio file provided"));
        }

        try {
            // 2. קריאה ללוגיקה העסקית (שממוקמת ב-AudioService)
            Map<String, Object> result = audioService.identifyAndCheckAccess(file, roomId);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            // 3. תיעוד השגיאה האמיתית בצד השרת בלבד (SLF4J)
            logger.error("Error processing audio access for room {}: {}", roomId, e.getMessage(), e);

            // 4. שמירת לוג הכישלון במסד הנתונים
            audioService.saveFailedLog(file.getOriginalFilename(), roomId);

            // 5. החזרת שגיאה עמומה וגנרית ללקוח (אבטחת מידע)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse());
        }
    }

    /**
     * פונקציית עזר ליצירת תשובת שגיאה בטוחה ונקייה (Safe Fallback).
     */
    private AccessResponse buildErrorResponse() {
        return new AccessResponse(
                false,
                "",
                0.0,
                "⛔ Server error during biometric processing" // הודעה גנרית שאינה חושפת סודות מערכת
        );
    }
}