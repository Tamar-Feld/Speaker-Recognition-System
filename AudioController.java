package com.example.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/audio")
@CrossOrigin(origins = "*")
public class AudioController {

    private static final String UPLOAD_DIR = "uploads";
    private static final int TIMEOUT_SECONDS = 15; // מקסימום זמן לכל תהליך

    // --- הזרקת חיבורים למסד הנתונים ---
    @Autowired
    private AccessLogRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoomPermissionRepository roomPermissionRepository; // חובה בשביל הדלתות!
    // --------------------------------------------

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("roomId") int roomId) { // חזרנו לקבל את מספר החדר!

        try {
            // === שלב 1: שמירת הקובץ ===
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) uploadDir.mkdirs();

            // שימוש ב-UUID כדי למנוע התנגשות אם שני אנשים נכנסים באותו רגע
            String originalName = file.getOriginalFilename();
            String extension = originalName.substring(originalName.lastIndexOf("."));
            String uniqueFileName = UUID.randomUUID().toString() + extension;

            Path filePath = Paths.get(UPLOAD_DIR, uniqueFileName);
            Files.write(filePath, file.getBytes());
            System.out.println("\n=== שלב 1: הקובץ נשמר (" + uniqueFileName + ") ===");

            // === הגדרת נתיבים דינמית ===
            String projectRoot = System.getProperty("user.dir");
            String serverPath = projectRoot.endsWith("server") ? projectRoot : projectRoot + File.separator + "server";

            String cppExe = serverPath + File.separator + "preprocessor.exe";
            String pythonPredictScript = serverPath + File.separator + "predict.py";
            String pythonDenoiseScript = serverPath + File.separator + "denoise.py";

            String originalWavPath = filePath.toString();
            String cleanWavPath = originalWavPath + "_denoised.wav";

            // ==========================================
            // === שלב 1.5: סינון רעשים (Spectral Gating) ===
            // ==========================================
            System.out.println("=== שלב 1.5: מפעיל מסנן רעשים (Python) ===");
            ProcessBuilder pbDenoise = new ProcessBuilder("python", pythonDenoiseScript, originalWavPath, cleanWavPath);
            pbDenoise.redirectErrorStream(true);
            Process processDenoise = pbDenoise.start();

            if (!processDenoise.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                processDenoise.destroyForcibly();
                System.err.println("⚠️ אזהרה: סינון רעשים נתקע. ממשיכים עם הקובץ המקורי.");
            }

            // החלטה איזה קובץ עובר ל-C++
            String fileForCpp = new File(cleanWavPath).exists() ? cleanWavPath : originalWavPath;

            // ==========================================
            // === שלב 2: הרצת C++ (Feature Extraction) ===
            // ==========================================
            System.out.println("=== שלב 2: הרצת C++ (חילוץ תכונות מתמטי) ===");
            if (new File(cppExe).exists()) {
                ProcessBuilder pbCpp = new ProcessBuilder(cppExe, fileForCpp);
                pbCpp.redirectErrorStream(true);
                Process processCpp = pbCpp.start();

                if (!processCpp.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    processCpp.destroyForcibly();
                    return ResponseEntity.status(500).body("Error: C++ Timeout");
                }
            } else {
                System.err.println("❌ שגיאה: קובץ ה-C++ לא נמצא!");
            }

            // ==========================================
            // === שלב 3: הרצת Python (AI Classification) ===
            // ==========================================
            System.out.println("=== שלב 3: הרצת Python (סיווג AI) ===");
            // ה-C++ מוציא CSV עם אותו שם של הקובץ שקיבל, פלוס ".csv"
            String csvFilePath = fileForCpp + ".csv";

            ProcessBuilder pbPy = new ProcessBuilder("python", pythonPredictScript, csvFilePath);
            pbPy.redirectErrorStream(true);
            Process processPy = pbPy.start();

            BufferedReader readerPy = new BufferedReader(new InputStreamReader(processPy.getInputStream()));
            String linePy;

            String detectedName = "Unknown";
            double confidence = 0.0;

            while ((linePy = readerPy.readLine()) != null) {
                System.out.println("[Python]: " + linePy);

                // תפיסת הנתונים מתוך הלוג של הפייתון
                if (linePy.startsWith("RESULT:")) {
                    String[] parts = linePy.split(":");
                    if (parts.length >= 3) {
                        detectedName = parts[1].trim().toLowerCase();
                        try { confidence = Double.parseDouble(parts[2].trim()); } catch (Exception e) {}
                    }
                }
            }

            if (!processPy.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                processPy.destroyForcibly();
                return ResponseEntity.status(500).body("Error: AI Processing timeout");
            }

            // מחיקת הקבצים הזמניים (ניקיון הדיסק!)
            Files.deleteIfExists(Paths.get(originalWavPath));
            Files.deleteIfExists(Paths.get(cleanWavPath));
            Files.deleteIfExists(Paths.get(csvFilePath));

            // ==========================================
            // === שלב 4: מנוע קבלת החלטות והרשאות SQL ===
            // ==========================================
            User user = userRepository.findByUsername(detectedName);
            boolean isApproved = false;
            String rejectionReason = "";

            if (confidence < 60.0) {
                rejectionReason = "זיהוי נמוך (" + confidence + "%)";
            } else if (user == null) {
                rejectionReason = "משתמש לא קיים במאגר";
            } else if (!user.isAuthorized()) {
                rejectionReason = "משתמש חסום (הרשאה נשללה)";
            } else {
                // בדיקת הרשאה ספציפית לחדר
                boolean hasRoomAccess = roomPermissionRepository.existsByUsernameAndRoomNumber(detectedName, roomId);
                if (hasRoomAccess) {
                    isApproved = true;
                    System.out.println("✅ אישור כניסה: " + detectedName + " לחדר " + roomId);
                } else {
                    rejectionReason = "אין הרשאה לחדר " + roomId;
                }
            }

            if (!isApproved) {
                System.out.println("⛔ נדחה: " + detectedName + " - " + rejectionReason);
            }

            // === שלב 5: שמירת לוג ===
            AccessLog log = new AccessLog(
                    originalName + " (Room " + roomId + ")",
                    detectedName,
                    String.format("%.2f%%", confidence),
                    isApproved
            );
            repository.save(log);

            // החזרת תשובה לדפדפן
            String clientResponse = isApproved ?
                    "✅ כניסה לחדר " + roomId + " אושרה עבור: " + detectedName :
                    "⛔ כניסה נדחתה: " + rejectionReason;

            return ResponseEntity.ok(clientResponse);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}