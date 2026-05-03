package com.example.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // === הפונקציה החדשה והחכמה ===
    // מקבלת: שם ורשימת קבצים
    @PostMapping("/register")
    public User registerUserAndTrain(
            @RequestParam("username") String username,
            @RequestParam("samples") MultipartFile[] samples) throws Exception {

        // 1. שמירת המשתמש ב-SQL
        User newUser = new User(username, true);
        userRepository.save(newUser);

        // 2. יצירת תיקייה ב-Dataset עבור המשתמש
        String projectRoot = System.getProperty("user.dir");
        String serverPath = projectRoot.endsWith("server") ? projectRoot : projectRoot + File.separator + "server";

        // נתיב: server/dataset/username
        Path userDatasetPath = Paths.get(serverPath, "dataset", username);
        if (!Files.exists(userDatasetPath)) {
            Files.createDirectories(userDatasetPath);
        }

        // 3. שמירת ההקלטות בתיקייה
        for (MultipartFile sample : samples) {
            Path filePath = userDatasetPath.resolve(sample.getOriginalFilename());
            Files.write(filePath, sample.getBytes());
        }
        System.out.println("✅ נשמרו " + samples.length + " הקלטות עבור: " + username);

        // 4. הרצת אימון מחדש (Re-training)
        System.out.println("🔄 מתחיל אימון מודל מחדש...");
        String pythonScript = serverPath + File.separator + "train_model.py";

        ProcessBuilder pb = new ProcessBuilder("python", pythonScript);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // קריאת הלוג של האימון
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("[Training]: " + line);
        }
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            System.out.println("✅ האימון הסתיים בהצלחה! המודל עודכן.");
        } else {
            System.err.println("❌ שגיאה באימון המודל.");
            throw new RuntimeException("Training failed");
        }

        return newUser;
    }
}