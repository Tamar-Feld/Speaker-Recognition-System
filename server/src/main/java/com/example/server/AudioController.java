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

@RestController
@RequestMapping("/api/audio")
@CrossOrigin(origins = "*")
public class AudioController {

    private static final String UPLOAD_DIR = "uploads";

    // ---  הזרקת החיבורים למסד הנתונים ---
    @Autowired
    private AccessLogRepository repository;

    @Autowired
    private UserRepository userRepository;
    // --------------------------------------------

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            // === שלב 1: שמירת הקובץ ===
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) uploadDir.mkdirs();

            Path filePath = Paths.get(UPLOAD_DIR, file.getOriginalFilename());
            Files.write(filePath, file.getBytes());
            System.out.println("=== שלב 1: הקובץ נשמר: " + filePath.toAbsolutePath() + " ===");

            // === הגדרת נתיבים ===
            String projectRoot = System.getProperty("user.dir");
            // נתיבים קשיחים כברירת מחדל
            String cppExe = "C:\\Users\\WIN 11\\Documents\\SpeakerAuth\\server\\preprocessor.exe";
            String pythonScript = "C:\\Users\\WIN 11\\Documents\\SpeakerAuth\\server\\predict.py";

            // בדיקת גיבוי: אם הנתיב הקשיח לא עובד, ננסה נתיב יחסי
            if (!new File(cppExe).exists() || !new File(pythonScript).exists()) {
                System.out.println("⚠️ נתיב קשיח לא נמצא, מנסה נתיב יחסי...");
                cppExe = projectRoot + File.separator + "preprocessor.exe";
                pythonScript = projectRoot + File.separator + "predict.py";
            }

            // === שלב 2: הרצת C++ ===
            System.out.println("\n=== שלב 2: הרצת C++ ===");

            // בדיקה שהקובץ קיים לפני שמנסים להריץ
            if (new File(cppExe).exists()) {
                ProcessBuilder pbCpp = new ProcessBuilder(cppExe, filePath.toString());
                pbCpp.redirectErrorStream(true);
                Process processCpp = pbCpp.start();

                BufferedReader readerCpp = new BufferedReader(new InputStreamReader(processCpp.getInputStream()));
                String lineCpp;
                while ((lineCpp = readerCpp.readLine()) != null) {
                    System.out.println("[C++]: " + lineCpp);
                }
                processCpp.waitFor();
            } else {
                System.err.println("❌ שגיאה: קובץ ה-C++ לא נמצא במיקום: " + cppExe);
            }

            // === שלב 3: הרצת Python ===
            System.out.println("\n=== שלב 3: הרצת Python ===");
            String csvFilePath = filePath.toString() + ".csv";

            ProcessBuilder pbPy = new ProcessBuilder("python", pythonScript, csvFilePath);
            pbPy.redirectErrorStream(true);
            Process processPy = pbPy.start();

            BufferedReader readerPy = new BufferedReader(new InputStreamReader(processPy.getInputStream()));
            String linePy;
            StringBuilder finalOutput = new StringBuilder();

            // --- תיקון 2: הגדרת המשתנה לפני הלולאה ---
            String detectedName = "Unknown";
            double confidence = 0.0;

            while ((linePy = readerPy.readLine()) != null) {
                System.out.println("[Python Log]: " + linePy); // הדפסה ללוג כדי שנראה מה קורה

                // בדיקה: האם זו שורת התוצאה שלנו?
                if (linePy.startsWith("RESULT:")) {
                    // פירוק המחרוזת לפי נקודותיים
                    // החלק ה-0 הוא "RESULT", החלק ה-1 הוא השם, החלק ה-2 הוא האחוזים
                    String[] parts = linePy.split(":");
                    if (parts.length >= 3) {
                        detectedName = parts[1].trim(); // הנה השם הנקי! (tamar)
                        confidence = Double.parseDouble(parts[2].trim()); // הנה האחוזים!
                    }
                }
            }
            processPy.waitFor();

            // === שלב 4: בדיקת הרשאות מול מסד הנתונים ===

            // 1. ניקוי שם (חילוץ "tamar")
            String cleanName = detectedName;
            if (detectedName.contains("Detected Speaker:")) {
                try {
                    cleanName = detectedName.split(":")[1].trim().split(" ")[0].toLowerCase();
                } catch (Exception e) {}
            }

            // 2. חיפוש בטבלה
            User user = userRepository.findByUsername(cleanName);
            boolean isApproved = false;

            // התנאי הפשוט: ביטחון מעל 40% וגם המשתמש מורשה
            // (ההנחה: user לא יהיה null כי המערכת מסונכרנת)
            if (confidence > 40.0 && user != null && user.isAuthorized()) {
                isApproved = true;
                System.out.println("✅ אושר: " + cleanName);
            } else {
                // כל סיבה אחרת (ביטחון נמוך / חסום)
                isApproved = false;
                System.out.println("⛔ נדחה: " + cleanName + " (ביטחון: " + confidence + "%)");
            }

            // 3. שמירת לוג
            AccessLog log = new AccessLog(
                    file.getOriginalFilename(), // השם המקורי לתצוגה
                    cleanName,
                    String.format("%.2f%%", confidence),
                    isApproved
            );
            repository.save(log);

            return ResponseEntity.ok(finalOutput.toString());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
