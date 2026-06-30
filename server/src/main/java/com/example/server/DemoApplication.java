package com.example.server;
import org.springframework.web.client.RestTemplate;
import com.example.server.entities.User;
import com.example.server.repositories.AdminRepository;
import com.example.server.repositories.UserRepository;
import com.example.server.services.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

// ═══════════════════════════════════════════════════════════════════
// DemoApplication — נקודת ההתחלה של השרת (Entry Point).
//
// ארכיטקטורה:
// בנוסף להפעלת Spring Boot, המחלקה מכילה מנגנון Bootstrapping (Seeding).
// המנגנון רץ אוטומטית פעם אחת בעליית השרת, מוודא שהסביבה מוכנה
// (תיקיות קיימות), ואם מסד הנתונים ריק לחלוטין - הוא מזריק נתוני
// ברירת מחדל (משתמשת 'tamar' ומנהל 'admin') כדי לאפשר כניסה ראשונית למערכת.
// ═══════════════════════════════════════════════════════════════════

@SpringBootApplication
public class DemoApplication {

    private static final Logger logger = LoggerFactory.getLogger(DemoApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
    /**
     * RestTemplate Bean עם Timeout מוגדר — חובה למניעת Thread Pool Exhaustion.
     * ללא timeout: בקשה תקועה ל-FastAPI חוסמת Tomcat thread לנצח.
     * Connect timeout: זמן עד לחיבור TCP (FastAPI down → כישלון מהיר).
     * Read timeout:    זמן עד לתגובה ראשונה (FastAPI תקוע → unblock אחרי 30s).
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);   // 3 שניות לחיבור TCP
        factory.setReadTimeout(30_000);     // 30 שניות לתגובה מה-AI (inference זמן)
        return new RestTemplate(factory);
    }
    /**
     * פונקציית אתחול אוטומטית (Bootstrapping) הרצה ברגע שספרינג מסיים לעלות.
     */
    @Bean
    public CommandLineRunner initDatabase(UserRepository userRepository,
                                          AdminRepository adminRepository,
                                          AdminService adminService) {
        return args -> {
            logger.info("🔄 מאתחל את מערכת ניהול הנתונים...");

            // 1. יצירת משתמשת ברירת מחדל (רק אם טבלת users ריקה לחלוטין)
            if (userRepository.count() == 0) {
                logger.info("➕ מסד הנתונים ריק. יוצר משתמשת ראשית 'tamar'...");
                // מניח שהבנאי של User מקבל: username, fullName, isAuthorized
                userRepository.save(new User("tamar", "tamar", true));
            }

            // 2. יצירת מנהל מערכת ראשוני (רק אם טבלת admins ריקה לחלוטין)
            if (adminRepository.count() == 0) {
                logger.info("➕ אין מנהל רשום. יוצר מנהל ראשי (admin)...");
                try {
                    adminService.createAdmin("admin", "admin123");
                    logger.warn("⚠️ שים לב: נוצר מנהל עם שם המשתמש 'admin' והסיסמה 'admin123'. יש לשנותה בהקדם!");
                } catch (DataIntegrityViolationException e) {
                    logger.info("ℹ️ מנהל 'admin' כבר קיים (נוצר על-ידי instance מקביל).");
                } catch (IllegalArgumentException e) {
                    logger.info("ℹ️ מנהל 'admin' כבר קיים במסד הנתונים.");
                }
            } else {
                logger.info("ℹ️ נמצאו {} מנהלים קיימים במסד הנתונים. Bootstrap מדולג.", adminRepository.count());
            }

            logger.info("✅ אתחול המערכת הושלם בהצלחה.");
        };
    }
}