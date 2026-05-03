package com.example.server;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.io.File;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner initDatabase(UserRepository userRepository) {
        return args -> {
            System.out.println("🔄 מבצע סנכרון בין תיקיות הדאטה למסד הנתונים...");

            // 1. מציאת נתיב ה-dataset
            String projectRoot = System.getProperty("user.dir");
            // תיקון נתיב למקרה שאנחנו רצים מתוך התיקייה הראשית או הפנימית
            String serverPath = projectRoot.endsWith("server") ? projectRoot : projectRoot + File.separator + "server";
            File datasetDir = new File(serverPath + File.separator + "dataset");

            // 2. בדיקה שהתיקייה קיימת
            if (datasetDir.exists() && datasetDir.isDirectory()) {
                File[] userFolders = datasetDir.listFiles(File::isDirectory);

                if (userFolders != null) {
                    for (File folder : userFolders) {
                        String folderName = folder.getName();

                        // 3. בדיקה מול ה-SQL: האם המשתמש כבר קיים?
                        if (userRepository.findByUsername(folderName) == null) {
                            // אם לא קיים - ניצור אותו אוטומטית כמשתמש מורשה
                            System.out.println("➕ נמצאה תיקייה חדשה: " + folderName + " -> מוסיף למשתמשים.");
                            userRepository.save(new User(folderName, true));
                        } else {
                            System.out.println("✔ המשתמש " + folderName + " כבר קיים ומסונכרן.");
                        }
                    }
                }
            } else {
                System.err.println("⚠️ לא נמצאה תיקיית dataset במיקום: " + datasetDir.getAbsolutePath());
                // יצירה ראשונית אם לא קיים
                datasetDir.mkdirs();
            }

            System.out.println("✅ סנכרון הושלם.");
        };
    }
}