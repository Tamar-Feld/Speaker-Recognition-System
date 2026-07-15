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

import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AudioService {

    private final AccessLogRepository accessLogRepository;
    private final UserRepository userRepository;
    private final RoomPermissionRepository roomPermissionRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final WebClient aiWebClient;

    @Value("${ai.server.url:http://127.0.0.1:8000/predict}")
    private String aiServerUrl;

    @Value("${ai.api.key}")
    private String aiApiKey;

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
                        RestTemplate restTemplate,
                        WebClient aiWebClient) {
        this.accessLogRepository = accessLogRepository;
        this.userRepository = userRepository;
        this.roomPermissionRepository = roomPermissionRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.aiWebClient = aiWebClient;
    }

    // ── הפעולה המרכזית: מזהה דובר ובודק הרשאת גישה לחדר ─────────────
    public Map<String, Object> identifyAndCheckAccess(MultipartFile file, int roomId, String clientIp) throws Exception {

        long requestStartTime = System.currentTimeMillis();
        System.out.println("\n📡 [Gateway] Access request for room " + roomId);

        String embeddingsJson = collectEnrolledEmbeddingsAsJson();
        Map<String, Object> aiBody = callPredictApi(file, embeddingsJson);

        if ("INSUFFICIENT_SPEECH".equals(aiBody.get("_rejection_code"))) {
            String logFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                    ? file.getOriginalFilename() : "audio.wav";
            long elapsed = System.currentTimeMillis() - requestStartTime;
            accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId,
                    clientIp, elapsed, "Insufficient speech detected (VAD)"));
            System.out.println("⛔ [Gateway] Rejected — insufficient speech in recording for room " + roomId);
            Map<String, Object> result = new HashMap<>();
            result.put("accessGranted", false);
            result.put("identifiedSpeaker", "unknown");
            result.put("confidence", 0.0);
            result.put("message", "⛔ Access denied — insufficient speech detected. Please speak clearly for the full recording duration.");
            return result;
        }

        Object matchedRaw = aiBody.get("matched_username");
        String detectedName = (matchedRaw != null) ? matchedRaw.toString() : null;

        double confidence = aiBody.get("confidence") != null ?
                ((Number) aiBody.get("confidence")).doubleValue() : 0.0;

        AccessDecision decision = evaluateAccess(detectedName, confidence, roomId);

        String cleanFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                ? file.getOriginalFilename() : "audio.wav";

        long elapsed = System.currentTimeMillis() - requestStartTime;
        accessLogRepository.save(new AccessLog(
                cleanFilename, detectedName, confidence, decision.approved(), roomId,
                clientIp, elapsed, decision.rejectionReason()));

        Map<String, Object> result = new HashMap<>();
        result.put("accessGranted", decision.approved());

        if (decision.approved()) {
            // ללקוח: מותר לחשוף את הכל — הגישה כבר אושרה
            result.put("identifiedSpeaker", detectedName);
            result.put("confidence", confidence);
            result.put("message", "✅ Access granted — welcome " + detectedName + ", door " + roomId + " is open");
            System.out.println("✅ [Gateway] Approved: " + detectedName + " → room " + roomId);
        } else {
            // ללקוח: תוצאה גנרית בלבד בדחייה — לא זהות, לא ציון, לא סיבה מדויקת
            result.put("identifiedSpeaker", "unknown");
            result.put("confidence", 0.0);
            result.put("message", "⛔ Access denied");
            // לשרת בלבד: הפרטים המלאים לצורך חקירה ודיבוג
            System.out.println("⛔ [Gateway] Denied: " + decision.rejectionReason()
                    + " (detected=" + detectedName + ", confidence=" + String.format("%.3f", confidence) + ")");
        }

        return result;
    }
    // ── שמירת לוג כשלון — נקרא מה-Controller בבלוק catch ───────────
    public void saveFailedLog(String originalFilename, int roomId, String clientIp) {
        try {
            String logFilename = (originalFilename != null && !originalFilename.isEmpty()) ? originalFilename : "audio.wav";
            accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId,
                    clientIp, null, "Server error during processing"));
        } catch (Exception logEx) {
            System.err.println("⚠️ [Gateway] Could not save error AccessLog: " + logEx.getMessage());
        }
    }

    // ── קריאה לא-חוסמת ל-Python, כולל שחזור התנהגות ה-422 הקיימת ──────────
    // למה זה היה בעיה: callPredictApi הישן עשה restTemplate.postForEntity(...) שגורם
    // ל-thread של ג'אווה "לשבת ולחכות" בלי לעשות כלום לאורך כל עיבוד Python
    // (FFmpeg + VAD + Fbank + הרצת המודל) — לפעמים שנייה-שתיים בכל בקשה.
    // מה השתנה: WebClient שולח את הבקשה ומחזיר Mono. עם toFuture() ה-thread
    // חוזר מיד למאגר של Tomcat; תוצאת Python מטופלת רק כשהיא מוכנה.
    // שחזור התנהגות 422: onErrorResume בודק קוד 422 ומחזיר sentinel map בדיוק
    // כמו שה-catch הישן עשה — אותה לוגיקה, רק בסגנון רֵאקטיבי.
    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<String, Object>> callPredictApiAsync(
            MultipartFile file, String embeddingsJson) throws IOException {

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty()
                        ? file.getOriginalFilename() : "audio.wav";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("embeddings", embeddingsJson);

        return aiWebClient.post()
                .uri(aiServerUrl)
                .header("X-Api-Key", aiApiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(WebClientResponseException.class, ex -> {
                    if (ex.getStatusCode().value() == 422) {
                        // Python דחה את האודיו כי אין מספיק דיבור (VAD).
                        // מחזירים sentinel map בדיוק כמו ה-catch הישן ב-callPredictApi.
                        Map<String, Object> sentinel = new HashMap<>();
                        sentinel.put("_rejection_code", "INSUFFICIENT_SPEECH");
                        return Mono.just(sentinel);
                    }
                    return Mono.error(ex);
                })
                .map(m -> (Map<String, Object>) m)
                .toFuture();
    }

    // ── גרסה אסינכרונית מלאה של תהליך הזיהוי ──────────────────────────────
    // למה זה עוזר: identifyAndCheckAccess הסינכרוני חסם thread עד שPython סיים.
    // כאן: קריאות ה-DB (collectEnrolledEmbeddingsAsJson, evaluateAccess, save)
    // נשארות סינכרוניות במכוון — הן מהירות ולא זה מה שהאט. רק קריאת ה-HTTP
    // ל-Python עוברת ל-WebClient. כשPython מחזיר תשובה, thenApply מריץ את
    // כל הלוגיקה העסקית הזהה לחלוטין לזו שב-identifyAndCheckAccess הסינכרוני.
    public CompletableFuture<Map<String, Object>> identifyAndCheckAccessAsync(
            MultipartFile file, int roomId, String clientIp) throws Exception {

        long requestStartTime = System.currentTimeMillis();
        System.out.println("\n📡 [Gateway] Access request for room " + roomId);

        String embeddingsJson = collectEnrolledEmbeddingsAsJson();   // סינכרוני — DB read

        return callPredictApiAsync(file, embeddingsJson).thenApply(aiBody -> {

            if ("INSUFFICIENT_SPEECH".equals(aiBody.get("_rejection_code"))) {
                String logFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                        ? file.getOriginalFilename() : "audio.wav";
                long elapsed = System.currentTimeMillis() - requestStartTime;
                accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId,
                        clientIp, elapsed, "Insufficient speech detected (VAD)"));
                System.out.println("⛔ [Gateway] Rejected — insufficient speech in recording for room " + roomId);
                Map<String, Object> result = new HashMap<>();
                result.put("accessGranted", false);
                result.put("identifiedSpeaker", "unknown");
                result.put("confidence", 0.0);
                result.put("message", "⛔ Access denied — insufficient speech detected. Please speak clearly for the full recording duration.");
                return result;
            }

            Object matchedRaw = aiBody.get("matched_username");
            String detectedName = (matchedRaw != null) ? matchedRaw.toString() : null;

            double confidence = aiBody.get("confidence") != null ?
                    ((Number) aiBody.get("confidence")).doubleValue() : 0.0;

            AccessDecision decision = evaluateAccess(detectedName, confidence, roomId);

            String cleanFilename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())
                    ? file.getOriginalFilename() : "audio.wav";

            long elapsed = System.currentTimeMillis() - requestStartTime;
            accessLogRepository.save(new AccessLog(
                    cleanFilename, detectedName, confidence, decision.approved(), roomId,
                    clientIp, elapsed, decision.rejectionReason()));

            Map<String, Object> result = new HashMap<>();
            result.put("accessGranted", decision.approved());

            if (decision.approved()) {
                result.put("identifiedSpeaker", detectedName);
                result.put("confidence", confidence);
                result.put("message", "✅ Access granted — welcome " + detectedName + ", door " + roomId + " is open");
                System.out.println("✅ [Gateway] Approved: " + detectedName + " → room " + roomId);
            } else {
                result.put("identifiedSpeaker", "unknown");
                result.put("confidence", 0.0);
                result.put("message", "⛔ Access denied");
                System.out.println("⛔ [Gateway] Denied: " + decision.rejectionReason()
                        + " (detected=" + detectedName + ", confidence=" + String.format("%.3f", confidence) + ")");
            }

            return result;
        });
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
        headers.set("X-Api-Key", aiApiKey);

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