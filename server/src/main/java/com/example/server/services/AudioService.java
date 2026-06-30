package com.example.server.services;

import com.example.server.entities.AccessLog;
import com.example.server.entities.User;
import com.example.server.repositories.AccessLogRepository;
import com.example.server.repositories.RoomPermissionRepository;
import com.example.server.repositories.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ═══════════════════════════════════════════════════════════════════
// AudioService — ליבת הלוגיקה העסקית: זיהוי קולי ביומטרי ובקרת הרשאות.
//
// ארכיטקטורה:
// - הזרקת תלויות (DI): שימוש ב-Constructor Injection (תקן תעשייה).
// - הגדרות סביבה: רף הביטחון (Confidence) וכתובת ה-AI נשלפים מקונפיגורציה.
// - מניעת טרנזקציות ארוכות: אין כאן @Transactional במכוון!
//   קריאת HTTP ל-FastAPI עשויה לקחת זמן (Blocking), ואנו לא רוצים
//   לנעול חיבור ל-MySQL (Connection Pool) בזמן ההמתנה.
// ═══════════════════════════════════════════════════════════════════

@Service
public class AudioService {

    private final AccessLogRepository accessLogRepository;
    private final UserRepository userRepository;
    private final RoomPermissionRepository roomPermissionRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.server.url:http://127.0.0.1:8000/predict}")
    private String aiServerUrl;

    // חילוץ "מספר הקסם" להגדרות - ברירת מחדל 60%
    @Value("${biometric.confidence.threshold:0.60}")
    private double confidenceThreshold;

    /** תוצאת ההחלטה העסקית: אושר/נדחה + הסיבה (כשנדחה). */
    private record AccessDecision(boolean approved, String rejectionReason) {}

    /** Constructor Injection */
    @Autowired
    public AudioService(AccessLogRepository accessLogRepository,
                        UserRepository userRepository,
                        RoomPermissionRepository roomPermissionRepository,
                        ObjectMapper objectMapper,
                        RestTemplate restTemplate) {
        this.accessLogRepository = accessLogRepository;
        this.userRepository = userRepository;
        this.roomPermissionRepository = roomPermissionRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    // ── הפעולה המרכזית: מזהה דובר ובודק הרשאת גישה לחדר ─────────────
    public Map<String, Object> identifyAndCheckAccess(MultipartFile file, int roomId) throws Exception {

        System.out.println("\n📡 [Gateway] Access request for room " + roomId);

        // Step 1: איסוף כל ה-embeddings הפעילים מ-MySQL
        String embeddingsJson = collectEnrolledEmbeddingsAsJson();

        // Step 2-3: שליחה ל-FastAPI /predict
        Map<String, Object> aiBody = callPredictApi(file, embeddingsJson);

        // Step 3b: VAD rejection — FastAPI returned 422 (insufficient speech)
        if ("INSUFFICIENT_SPEECH".equals(aiBody.get("_rejection_code"))) {
            String logFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                    ? file.getOriginalFilename() : "audio.wav";
            accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId));
            System.out.println("⛔ [Gateway] Rejected — insufficient speech in recording for room " + roomId);
            Map<String, Object> result = new HashMap<>();
            result.put("accessGranted", false);
            result.put("identifiedSpeaker", "unknown");
            result.put("confidence", 0.0);
            result.put("message", "⛔ Access denied — insufficient speech detected. Please speak clearly for the full recording duration.");
            return result;
        }

        // Step 4: פענוח תשובת ה-AI
        Object matchedRaw = aiBody.get("matched_username");
        String detectedName = (matchedRaw != null) ? matchedRaw.toString() : null;

        // המרה בטוחה ל-Double (מונע קריסות אם Jackson מפרש כ-Integer)
        double confidence = aiBody.get("confidence") != null ?
                ((Number) aiBody.get("confidence")).doubleValue() : 0.0;

        // Step 5: לוגיקה עסקית — אישור/דחייה
        AccessDecision decision = evaluateAccess(detectedName, confidence, roomId);

        // Step 6: שמירת AccessLog
        String cleanFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                ? file.getOriginalFilename() : "audio.wav";

        accessLogRepository.save(new AccessLog(
                cleanFilename, detectedName, confidence, decision.approved(), roomId));

        // Step 7: בניית תשובה ל-React
        Map<String, Object> result = new HashMap<>();
        result.put("accessGranted", decision.approved());
        result.put("identifiedSpeaker", detectedName != null ? detectedName : "unknown");
        result.put("confidence", confidence);

        if (decision.approved()) {
            result.put("message", "✅ Access granted — welcome " + detectedName + ", door " + roomId + " is open");
            System.out.println("✅ [Gateway] Approved: " + detectedName + " → room " + roomId);
        } else {
            result.put("message", "⛔ Access denied — " + decision.rejectionReason());
            System.out.println("⛔ [Gateway] Denied: " + decision.rejectionReason());
        }

        return result;
    }

    // ── שמירת לוג כשלון — נקרא מה-Controller בבלוק catch ───────────
    public void saveFailedLog(String originalFilename, int roomId) {
        try {
            String logFilename = (originalFilename != null && !originalFilename.isEmpty()) ? originalFilename : "audio.wav";
            accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId));
        } catch (Exception logEx) {
            System.err.println("⚠️ [Gateway] Could not save error AccessLog: " + logEx.getMessage());
        }
    }

    // ── Helper: איסוף embeddings רשומים ────────────────────────────
    private String collectEnrolledEmbeddingsAsJson() throws Exception {
        Map<String, Object> embeddingsMap = new HashMap<>();
        for (User u : userRepository.findAll()) {
            // סינון חשוב: אין לשלוח לפייתון עובדים שאינם מורשים/חסומים!
            if (!u.isAuthorized()) continue;

            String raw = u.getBiometricEmbedding();
            if (raw == null || raw.isBlank()) continue;

            try {
                List<Double> emb = objectMapper.readValue(raw, new TypeReference<>() {});
                embeddingsMap.put(u.getUsername(), emb);
            } catch (Exception e) {
                System.err.println("⚠️ [Gateway] Could not parse embedding for user: "
                        + u.getUsername() + " — " + e.getMessage());
            }
        }
        System.out.println("📦 [Gateway] Sending " + embeddingsMap.size()
                + " enrolled embedding(s) to FastAPI.");
        return objectMapper.writeValueAsString(embeddingsMap);
    }

    // ── Helper: קריאה ל-FastAPI /predict ───────────────────────────
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPredictApi(MultipartFile file, String embeddingsJson) throws Exception {
        ByteArrayResource audioResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty()
                        ? file.getOriginalFilename() : "audio.wav";
            }
        };

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(audioResource, fileHeaders));
        body.add("embeddings", embeddingsJson);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        long t0 = System.currentTimeMillis();
        ResponseEntity<Map> aiResponse;
        try {
            aiResponse = restTemplate.postForEntity(aiServerUrl, request, Map.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                // FastAPI rejected audio due to insufficient speech (SPEECH_RATIO_TOO_LOW).
                // Return a sentinel map so the caller can surface a meaningful message.
                Map<String, Object> sentinel = new HashMap<>();
                sentinel.put("_rejection_code", "INSUFFICIENT_SPEECH");
                return sentinel;
            }
            throw e;
        }
        System.out.println("⚡ [AI] Analysis done in " + (System.currentTimeMillis() - t0) + "ms");

        if (aiResponse.getStatusCode() != HttpStatus.OK || aiResponse.getBody() == null) {
            throw new IllegalStateException("AI server returned no valid response");
        }
        return aiResponse.getBody();
    }

    // ── Helper: לוגיקה עסקית של אישור/דחייה ────────────────────────
    private AccessDecision evaluateAccess(String detectedName, double confidence, int roomId) {
        if (detectedName == null) {
            return new AccessDecision(false, "No enrolled speakers to match against");
        }
        // שימוש במשתנה המוגדר (confidenceThreshold) במקום המספר הקשיח 0.60
        if (confidence < confidenceThreshold) {
            return new AccessDecision(false, "Biometric confidence too low ("
                    + String.format("%.1f", confidence * 100) + "%)");
        }

        // שימוש ב-Optional (תואם לתיקונים שעשינו בשאר ה-Repositories)
        User user = userRepository.findByUsername(detectedName).orElse(null);

        if (user == null) {
            return new AccessDecision(false, "Speaker '" + detectedName + "' not registered in the system");
        }
        if (!user.isAuthorized()) {
            return new AccessDecision(false, "Access revoked (user blocked)");
        }

        boolean hasAccess = roomPermissionRepository.existsByUsernameAndRoomNumber(detectedName, roomId);
        if (!hasAccess) {
            return new AccessDecision(false, "No permission for room " + roomId);
        }
        return new AccessDecision(true, null);
    }
}