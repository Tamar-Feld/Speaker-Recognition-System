package com.example.server.services;

import com.example.server.entities.RoomPermission;
import com.example.server.entities.User;
import com.example.server.repositories.RoomPermissionRepository;
import com.example.server.repositories.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// ═══════════════════════════════════════════════════════════════════
// UserService — ליבת הלוגיקה העסקית: רישום ביומטרי וניהול הרשאות.
//
// ארכיטקטורה:
// - Constructor Injection לניהול תלויות (Best Practice).
// - שימוש ב-SLF4J לתיעוד תהליכים במקום System.out המיושן.
// - ניהול טרנזקציות: @Transactional מוגדר רק על פעולות DB טהורות.
//   פעולת הרישום (registerAndEnroll) אינה טרנזקטיבית ברמת המעטפת,
//   כדי למנוע נעילת DB בזמן המתנה ארוכה לרשת (FastAPI) או לדיסק.
// - עבודה נכונה עם Optional וסינון קבצים פגומים.
// ═══════════════════════════════════════════════════════════════════

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RoomPermissionRepository roomPermissionRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.enroll.url:http://127.0.0.1:8000/enroll}")
    private String aiEnrollUrl;

    @Value("${ai.api.key}")
    private String aiApiKey;
    /**
     * Constructor Injection
     */
    @Autowired
    public UserService(UserRepository userRepository,
                       RoomPermissionRepository roomPermissionRepository,
                       ObjectMapper objectMapper,
                       RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.roomPermissionRepository = roomPermissionRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User toggleAuthorization(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User ID " + id + " not found"));
        user.setAuthorized(!user.isAuthorized());
        return userRepository.save(user);
    }

    public List<Integer> getUserRooms(String username) {
        return roomPermissionRepository.findByUsername(username)
                .stream()
                .map(RoomPermission::getRoomNumber)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Integer> toggleUserRoom(String username, int roomNum) {
        if (roomPermissionRepository.existsByUsernameAndRoomNumber(username, roomNum)) {
            roomPermissionRepository.deleteByUsernameAndRoomNumber(username, roomNum);
        } else {
            roomPermissionRepository.save(new RoomPermission(username, roomNum));
        }
        return getUserRooms(username);
    }

    /**
     * רישום משתמש + enrollment ביומטרי.
     */
    public String registerAndEnroll(String username, String fullName, MultipartFile[] samples) throws Exception {

        if (samples == null || samples.length < 3) {
            throw new IllegalArgumentException("נדרשות לפחות שלוש הקלטות קוליות לרישום ביומטרי.");
        }

        logger.info("🆕 [Gateway] Registering user: {} ({} samples)", username, samples.length);

        List<List<Double>> allEmbeddings = enrollSamplesViaAi(samples);

        if (allEmbeddings.size() < 3) {
            throw new IllegalStateException(
                    "רק " + allEmbeddings.size() + " מתוך " + samples.length
                            + " הקלטות עובדו בהצלחה. נדרשות לפחות שלוש הקלטות תקינות לרישום.");
        }

        double[] mean = averageAndNormalize(allEmbeddings);
        persistEmbedding(username, fullName, mean);

        logger.info("✅ [Gateway] Enrollment completed successfully for user: {}", username);
        return "User '" + username + "' enrolled successfully using " + allEmbeddings.size() + " valid samples.";
    }

    // ── Helper: שליחת כל sample בנפרד ל-FastAPI /enroll ─────────────
    @SuppressWarnings("unchecked")
    private List<List<Double>> enrollSamplesViaAi(MultipartFile[] samples) {
        logger.info("📡 [Gateway] Sending samples to AI for enrollment...");

        List<List<Double>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < samples.length; i++) {
            MultipartFile sample = samples[i];

            if (sample.isEmpty()) continue; // סינון קבצים ריקים

            try {
                ByteArrayResource res = new ByteArrayResource(sample.getBytes()) {
                    @Override
                    public String getFilename() {
                        return sample.getOriginalFilename() != null && !sample.getOriginalFilename().isEmpty()
                                ? sample.getOriginalFilename() : "sample.wav";
                    }
                };

                HttpHeaders fileHdr = new HttpHeaders();
                fileHdr.setContentType(MediaType.APPLICATION_OCTET_STREAM);

                MultiValueMap<String, Object> enrollBody = new LinkedMultiValueMap<>();
                enrollBody.add("file", new HttpEntity<>(res, fileHdr));

                HttpHeaders enrollHdr = new HttpHeaders();
                enrollHdr.setContentType(MediaType.MULTIPART_FORM_DATA);
                enrollHdr.set("X-Api-Key", aiApiKey);

                ResponseEntity<Map> aiResp = restTemplate.postForEntity(
                        aiEnrollUrl, new HttpEntity<>(enrollBody, enrollHdr), Map.class);

                if (aiResp.getStatusCode() == HttpStatus.OK && aiResp.getBody() != null) {
                    Object rawEmb = aiResp.getBody().get("embedding");
                    if (rawEmb != null) {
                        List<Double> emb = objectMapper.convertValue(rawEmb, new TypeReference<>() {});
                        if (emb.size() != 192) {
                            logger.warn("⚠️ [Gateway] Sample {} returned embedding of size {}, expected 192 — skipped.", (i + 1), emb.size());
                        } else {
                            allEmbeddings.add(emb);
                            logger.debug("✓ Sample {}/{} processed successfully.", (i + 1), samples.length);
                        }
                    }
                }
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                    logger.warn("⚠️ [Gateway] Sample {} rejected — insufficient speech detected (VAD). Skipped.", (i + 1));
                } else {
                    logger.error("⚠️ [Gateway] Sample {} failed (HTTP {}): {}", (i + 1), e.getStatusCode(), e.getMessage());
                }
            } catch (Exception e) {
                logger.error("⚠️ [Gateway] Sample {} failed: {}", (i + 1), e.getMessage());
            }
        }
        return allEmbeddings;
    }

    // ── Helper: ממוצע + נירמול L2 ───────────────────────────────────
    private double[] averageAndNormalize(List<List<Double>> allEmbeddings) {
        double[] mean = new double[192];
        for (List<Double> emb : allEmbeddings) {
            for (int i = 0; i < 192; i++) mean[i] += emb.get(i);
        }
        for (int i = 0; i < 192; i++) mean[i] /= allEmbeddings.size();

        double norm = 0;
        for (double v : mean) norm += v * v;
        norm = Math.sqrt(norm);

        if (norm > 1e-9) {
            for (int i = 0; i < 192; i++) mean[i] /= norm;
        }
        return mean;
    }

    // ── Helper: שמירת ה-embedding הסופי ב-MySQL ─────────────────────
    @Transactional // חובה טרנזקציה כי אנו מבצעים יצירה/עדכון במסד נתונים
    protected void persistEmbedding(String username, String fullName, double[] mean) throws Exception {
        List<Double> finalEmbList = new ArrayList<>();
        for (double v : mean) finalEmbList.add(v);
        String embJson = objectMapper.writeValueAsString(finalEmbList);

        String displayName = (fullName != null && !fullName.isBlank()) ? fullName : username;
        User enrolled = userRepository.findByUsername(username)
                .orElseGet(() -> new User(username, displayName, true));

        if (fullName != null && !fullName.isBlank()) {
            enrolled.setFullName(displayName);
        }
        enrolled.setBiometricEmbedding(embJson);
        userRepository.save(enrolled);

        logger.info("💾 [Gateway] Embedding persisted to MySQL for user: {}", username);
    }
}