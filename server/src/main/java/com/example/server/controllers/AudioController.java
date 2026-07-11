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
    // ── הגנה נגד replay (H-2): timestamp + nonce ──────────────────
    private final java.util.Map<String, Long> usedNonces = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TIMESTAMP_WINDOW_MS = 30_000; // חלון קבלה: 30 שניות

    // ── הגבלת קצב לפי חדר (L-1) ───────────────────────────────────
    private final java.util.Map<Integer, java.util.List<Long>> uploadTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS_PER_WINDOW = 10;
    private static final long RATE_WINDOW_MS = 60_000; // חלון: 60 שניות

    private boolean isRateLimited(int roomId) {
        long now = System.currentTimeMillis();
        var timestamps = uploadTimestamps.computeIfAbsent(
                roomId, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        synchronized (timestamps) {
            timestamps.removeIf(t -> now - t > RATE_WINDOW_MS);
            if (timestamps.size() >= MAX_ATTEMPTS_PER_WINDOW) return true;
            timestamps.add(now);
            return false;
        }
    }

    private void cleanupExpiredNonces(long now) {
        usedNonces.entrySet().removeIf(e -> now - e.getValue() > TIMESTAMP_WINDOW_MS * 2);
    }

    // ── DTO ──────────────────────────────────────────────────────────
    // מחלקה פנימית בטוחה (Type-Safe) המחליפה את השימוש ב-Map גנרי חסר סוג
    public record AccessResponse(boolean accessGranted, String identifiedSpeaker, double confidence, String message) {}

    // ── Endpoints ──────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("roomId") int roomId,
            @RequestParam("timestamp") long timestamp,
            @RequestParam("nonce") String nonce,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        String clientIp = httpRequest.getRemoteAddr();
        long now = System.currentTimeMillis();
        // 1. הגבלת קצב —שלא ישלחו ברציפות לנסות לנחש
        if (isRateLimited(roomId)) {
            logger.warn("Rate limit exceeded for room {}", roomId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new AccessResponse(false, "unknown", 0.0, "⛔ יותר מדי ניסיונות, נסי שוב בעוד רגע"));
        }
        // 2. חלון זמן — דוחה בקשות ישנות שנתפסו ונשלחות מאוחר
        if (Math.abs(now - timestamp) > TIMESTAMP_WINDOW_MS) {
            logger.warn("Rejected stale/future request for room {} (diff: {}ms)", roomId, now - timestamp);
            return ResponseEntity.badRequest()
                    .body(new AccessResponse(false, "unknown", 0.0, "⛔ הבקשה פגה תוקף, נסי שוב"));
        }

        // 3. nonce ייחודי — דוחה שידור חוזר של אותה בקשה בדיוק
        cleanupExpiredNonces(now);
        if (usedNonces.putIfAbsent(nonce, now) != null) {
            logger.warn("Rejected replayed request for room {} (duplicate nonce)", roomId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AccessResponse(false, "unknown", 0.0, "⛔ בקשה כפולה זוהתה"));
        }
        // 1. ולידציה בסיסית של הקלט (Guard Clause)
        if (file.isEmpty()) {
            logger.warn("Received empty audio file for room {}", roomId);
            return ResponseEntity.badRequest()
                    .body(new AccessResponse(false, "unknown", 0.0, "⛔ Error: No audio file provided"));
        }

        try {
            // 2. קריאה ללוגיקה העסקית (שממוקמת ב-AudioService)
            Map<String, Object> result = audioService.identifyAndCheckAccess(file, roomId, clientIp);            return ResponseEntity.ok(result);

        } catch (Exception e) {
            // 3. תיעוד השגיאה האמיתית בצד השרת בלבד (SLF4J)
            logger.error("Error processing audio access for room {}: {}", roomId, e.getMessage(), e);

            // 4. שמירת לוג הכישלון במסד הנתונים
            audioService.saveFailedLog(file.getOriginalFilename(), roomId, clientIp);
            // 5. החזרת שגיאה עמומה וגנרית ללקוח (אבטחת מידע)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse());
        }
    }

    /**
     * פונקציית עזר ליצירת תשובת שגיאה בטוחה ונקייה .
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