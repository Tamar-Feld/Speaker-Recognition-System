# דוח אבטחה — SpeakerAuth Biometric System
**תאריך:** 2026-07-05  
**גרסה:** ביקורת מלאה על כל שכבות המערכת

---

## תוכן עניינים
1. [סיכום מנהלים](#סיכום-מנהלים)
2. [קריטי — עוקף אימות ביומטרי](#קריטי)
3. [גבוה — פגיעות משמעותיות](#גבוה)
4. [בינוני — חולשות תפעוליות](#בינוני)
5. [נמוך — שיפורים מומלצים](#נמוך)
6. [רשימת תיקונים מפורטת](#תיקונים)

---

## סיכום מנהלים

הביקורת מצאה **4 פגיעויות קריטיות** שיחד מאפשרות עקיפה מוחלטת של האימות הביומטרי — מבלי לדבר כמו המשתמש המורשה ומבלי לגנוב ציוד. שורש הבעיה המרכזית: FastAPI (port 8000) נגיש ישירות ללא שום אימות, ומקבל את ה-embeddings הרשומים כקלט חיצוני ולא כמידע פנימי מאובטח.

---

## קריטי

### [C-1] Prototype Injection — עקיפה מוחלטת של האימות הביומטרי

**קובץ:** `ai_engine/api.py:228`  
**קובץ:** `server/src/main/java/.../services/AudioService.java:164-181`

**המנגנון הפגיע:**
```python
@app.post("/predict")
def predict_speaker(file: UploadFile = File(...), embeddings: str = Form(...)):
```
פרמטר `embeddings` הוא **קלט חיצוני שנשלט לחלוטין על ידי הקורא** — JSON של ייצוגים מספריים (וקטורים 192-ממדיים) שמשמשים כ"בסיס הנתונים" לזיהוי. FastAPI לא שומר שום מצב פנימי; הוא סומך שהמידע שהתקבל אמיתי.

**התקיפה (3 שלבים, ~30 שניות):**

```bash
# שלב 1: קבל את הייצוג המספרי של הקול שלך מה-/enroll הפתוח
curl -X POST http://localhost:8000/enroll \
  -F "file=@my_voice.wav"
# תגובה: {"embedding": [0.032, -0.041, 0.019, ..., 192 ערכים]}

# שלב 2: שלח ישירות ל-FastAPI —
#   audio: הקול שלך
#   embeddings: מיפוי של הייצוג שלך לשם "alice" (משתמשת מורשית)
curl -X POST http://localhost:8000/predict \
  -F "file=@my_voice.wav" \
  -F 'embeddings={"alice": [0.032, -0.041, 0.019, ...]}'

# תוצאה: {"matched_username": "alice", "confidence": 0.99}
```

**שלב 3:** הפורץ מקבל זהות "alice" עם ביטחון מקסימלי — מבלי לדבר כמו alice, מבלי לגנוב כלום, רק בגלל שה-FastAPI מוגש את הנתונים שהתוקף עצמו שלח.

**עוצמת הנזק:** עוקף את כל שכבות ה-Spring Boot — בדיקת `isAuthorized`, סף הביטחון 60%, הרשאות חדר, שמירת לוג אמיתי. מה-FastAPI ניתן לקבל "הצלחה" אך אין כניסה דרך ה-Spring Boot (כי הוא לא קיבל את הבקשה). אם התוקף משכפל גם את בקשת ה-Spring (ראה [C-2]), הכניסה מורשית לחלוטין.

---

### [C-2] FastAPI פתוח לחלוטין ללא אימות — עוקף את כל ה-Spring Boot

**קובץ:** `ai_engine/api.py:83` (אין שום middleware אימות)

FastAPI רץ על port 8000 ללא:
- API key
- IP whitelist
- שום הגנה

**ההשלכה:** כל בקשה שה-Spring Boot שולח אפשר לשכפל ישירות. תוקף שיש לו גישה ל-localhost (או לרשת הפנימית) יכול:

1. לקרוא ל-`/predict` עם embedding בדוי → זיהוי מזויף (ראה C-1)
2. לקרוא ל-`/enroll` עם כל קובץ אודיו → לקבל embedding (ראה C-3)
3. **לעקוף לחלוטין** את כל הלוגיקה ב-`AudioService.java`: בדיקת `isAuthorized`, בדיקת הרשאות חדר, שמירת `AccessLog`

**תרחיש ריאלי:** עובד שנחסם (`is_authorized = false`) עדיין יכול לקרוא ל-FastAPI ישירות ולקבל אישור ביומטרי — כי בדיקת החסימה קיימת רק ב-Spring Boot.

---

### [C-3] /enroll מחזיר Embedding בחינם ללא אימות

**קובץ:** `ai_engine/api.py:188-224`

```python
@app.post("/enroll")
def enroll_speaker(file: UploadFile = File(...)):
    ...
    return {"embedding": emb.tolist()}  # 192 ערכי float נחשפים
```

**מה אפשר לעשות עם זה:**

1. **מיפוי ביומטרי:** שלח הקלטות של משתמשים שונים → קבל את הייצוג המספרי המדויק של כל אחד
2. **הפוך הנדסה:** בנה מסד נתונים של embeddings לפני ביצוע C-1
3. **בדוק קרבה:** ניתן להשוות embeddings ולבדוק אם שני קלטים "שייכים לאותו אדם" בלי לגשת ל-MySQL
4. **Embedding Capture:** אם תוקף הצליח לצלם/לקלוט הקלטה קולית של alice (מטלפון, מפגישה), הוא שולח ל-`/enroll` ומקבל את הייצוג של alice — ואז משתמש בו ב-C-1

---

### [C-4] JWT Secret קשיח בקוד המקור — זיוף טוקן Admin

**קובץ:** `server/src/main/resources/application.properties:7`

```properties
jwt.secret=a-very-long-and-secure-secret-key-for-my-app-must-be-at-least-32-chars!
```

**הבעיה:** כל מי שיש לו גישה לקוד המקור (GitHub, zip, גיבוי) יכול:
1. לקחת את המפתח הזה
2. לייצר JWT חתום עם כל שם משתמש שירצה
3. לשלוח אותו ב-`Authorization: Bearer <token>` לכל endpoint ב-Spring Boot

**קוד יצירת טוקן מזויף (Python):**
```python
import jwt, datetime
token = jwt.encode(
    {"sub": "admin", "iat": datetime.datetime.utcnow(),
     "exp": datetime.datetime.utcnow() + datetime.timedelta(hours=10)},
    "a-very-long-and-secure-secret-key-for-my-app-must-be-at-least-32-chars!",
    algorithm="HS256"
)
# token זה יתקבל כחוקי לחלוטין על ידי JwtFilter
```

**עוצמת הנזק:** גישה מלאה ל-`/api/users`, `/api/logs`, `/api/users/register`, `/api/users/{id}/toggle`, ו-`/api/admin/reset-password` — כל ה-API המוגן.

---

## גבוה

### [H-1] ברירות מחדל: admin/admin123 נוצרים אוטומטית

**קובץ:** `server/src/main/java/.../DemoApplication.java:65-70`

```java
adminService.createAdmin("admin", "admin123");
logger.warn("⚠️ שים לב: נוצר מנהל עם שם המשתמש 'admin' והסיסמה 'admin123'...");
```

אם המנהל לא שינה את הסיסמה, כל מי שיודע שהמערכת מותקנת יכול לנסות `admin` / `admin123` ולקבל JWT תקף מיד.

**הבעיה הנוספת:** אין Rate Limiting על `POST /api/admin/login` — תוקף יכול לנסות אינסוף סיסמאות בלי נעילה.

---

### [H-2] Replay Attack — שליחה חוזרת של קובץ אודיו לגיטימי

**קובץ:** `server/src/main/java/.../controllers/AudioController.java:49-57`

בקשת `/api/audio/upload` מקבלת קובץ אודיו + roomId בלי שום:
- Timestamp/Nonce קשור לבקשה
- Session token ייחודי לבקשה
- בדיקת "כבר השתמשת בקובץ הזה"

**תרחיש:** תוקף יירוט תעבורה (HTTP, לא HTTPS) → צולם קובץ WAV של alice + roomId שנשלחו → משלח מחדש את אותו הבקשה → alice מזוהה שוב ושוב.

אין מגבלה על כמות הניסיונות — ניתן לשלוח אלפי בקשות.

---

### [H-3] Mass Enrollment Attack — שימוש לרעה ב-/api/users/register

**קובץ:** `server/src/main/java/.../controllers/UserController.java:71-96`

`POST /api/users/register` מוגן ב-JWT — אבל אם JWT מזויף (ראה C-4) או נגנב, תוקף יכול:
1. לרשום כמות בלתי מוגבלת של "משתמשים" עם הקלטות דמה
2. לגרום ל-FastAPI לעבד כמויות עצומות של שמע (DoS)
3. "להכביד" על `collectEnrolledEmbeddingsAsJson()` שסורקת את כל המשתמשים בכל קריאת `/predict`

---

### [H-4] Embedding החלפה ישירה ב-MySQL — זיוף זהות ביומטרית

**קובץ:** `server/src/main/java/.../entities/User.java:44`

```java
@Column(name = "biometric_embedding", nullable = true, columnDefinition = "TEXT")
private String biometricEmbedding;
```

ה-embedding מאוחסן כ-TEXT גולמי ב-MySQL. אם תוקף מקבל גישה ל-MySQL (סיסמה `1234` הידועה בקוד), הוא יכול:

```sql
-- שלב 1: קבל את הייצוג שלך מ-/enroll
-- שלב 2: החלף את ה-embedding של alice בשלך
UPDATE users
SET biometric_embedding = '[0.032, -0.041, ..., 192 ערכים שלי]'
WHERE username = 'alice';
```

מעכשיו הקול של התוקף מזוהה בתור alice על ידי המערכת הלגיטימית — דרך ה-Spring Boot עצמו.

---

### [H-5] /api/admin/reset-password ללא הגבלות

**קובץ:** `server/src/main/java/.../controllers/AdminController.java:71-79`

```java
@PostMapping("/reset-password")
public ResponseEntity<BasicResponse> resetPassword(@RequestBody ResetPasswordRequest request) {
```

מי שיש לו JWT תקף יכול:
- לאפס את הסיסמה של **כל** מנהל (לא רק שלו)
- אין בדיקה שה-`username` בבקשה תואם למשתמש שהוציא את ה-JWT
- **השפעה:** אם JWT אחד נפרץ (ראה C-4 או H-1), כל חשבונות המנהל נפרצים גם הם

---

## בינוני

### [M-1] CORS עם allowCredentials + wildcard — עירוב מסוכן

**קובץ:** `server/src/main/java/.../config/SecurityConfig.java:82-85`

```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```

שילוב זה מאפשר לאתר זדוני לבצע בקשות לשרת עם credentials (cookies, JWT) של המשתמש.  
**תרחיש:** אדמין מחובר מבקר באתר זדוני שמבצע AJAX calls עם JWT שלו.

---

### [M-2] אין ולידציה על מימד ה-embeddings ב-/predict

**קובץ:** `ai_engine/api.py:238-243`

```python
for username, emb_list in candidates_raw.items():
    vec = torch.tensor(emb_list, dtype=torch.float32)
    candidates[username] = F.normalize(vec, p=2, dim=0)
```

אין בדיקה ש-`len(emb_list) == 192`. ניתן לשלוח:
- וקטור קצר: ידרדר את חישוב cosine similarity
- וקטור ארוך מאוד: בזבוז זיכרון
- ערכים לא-מספריים: כישלון `torch.tensor` → דילוג שקט

---

### [M-3] קבצים זמניים על הדיסק — פרטיות

**קובץ:** `ai_engine/api.py:86-119`

```python
temp_input  = os.path.join(temp_dir, f"raw_audio_{uuid.uuid4().hex}.webm")
temp_output = os.path.join(temp_dir, f"converted_audio_{uuid.uuid4().hex}.wav")
```

קבצי אודיו ביומטריים נכתבים לדיסק ב-`tempfile.gettempdir()`. בתהליך שנקטע חריגה לפני `finally`, הקובץ עלול להישאר. הלוגיקה מנסה לנקות אבל לא מבטיחה זאת לחלוטין בכל מסלולי השגיאה.

---

### [M-4] JWT אינו ניתן לביטול (No Token Revocation)

**קובץ:** `server/src/main/java/.../config/JwtUtil.java`

אין blacklist/revocation mechanism. JWT שנגנב תקף עד לפקיעתו (10 שעות). אין logout "אמיתי" — רק מחיקה מ-sessionStorage בדפדפן, שלא מבטלת את הטוקן בצד השרת.

---

### [M-5] AccessLog חסר IP ו-ProcessingTime בבנאי

**קובץ:** `server/src/main/java/.../entities/AccessLog.java:46-53`

```java
public AccessLog(String filename, String identifiedSpeaker,
                 double confidence, boolean accessGranted, int roomNumber) {
    // ip_address ו-processing_time_ms לא נשמרים
}
```

לוגי גישה ללא IP address מקשים על חקירת אירועים. שדות אלה מוגדרים ב-Entity אבל לא מאוכלסים בפועל.

---

### [M-6] סיסמת MySQL ידועה בקוד המקור

**קובץ:** `server/src/main/resources/application.properties:14-15`

```properties
spring.datasource.username=root
spring.datasource.password=1234
```

גישה ישירה ל-MySQL עם `root/1234` מאפשרת קריאה וכתיבה של כל הנתונים, כולל embeddings ביומטריים וסיסמאות admin (Bcrypt).

---

## נמוך

### [L-1] אין Rate Limiting על /api/audio/upload

כמות ניסיונות האימות הביומטרי אינה מוגבלת — ניתן לשגר בקשות אוטומטיות רבות.

### [L-2] אין ולידציה על סוג קובץ האודיו לפני FFmpeg

הקוד שולח כל קובץ ל-FFmpeg בלי בדיקה מוקדמת. קובץ zip bomb קטן (10MB) עלול להפוך לקובץ ענק לאחר עיבוד.

### [L-3] @CrossOrigin(origins = "*") על כל הבקרים

מוגדר גם ב-`@CrossOrigin` וגם ב-`SecurityConfig` — כפילות שמקשה על תחזוקה.

### [L-4] הודעות שגיאה עלולות לחשוף מידע ב-logs

`AudioService.java:157` מדפיס username לשגיאות parsing — ב-logs השרת שנחשפים.

---

## תיקונים

---

### [תיקון C-1 + C-2 + C-3] — הוסף API Key ל-FastAPI

**עוצמה:** פותר C-1 (Prototype Injection), C-2 (FastAPI פתוח), C-3 (Embedding leak)  
**מורכבות יישום:** נמוכה — כ-15 שורות קוד

**ב-FastAPI (`api.py`):**
```python
import secrets
from fastapi import Header, HTTPException

AI_API_KEY = os.environ.get("AI_API_KEY", "")  # מגיע ממשתנה סביבה

def verify_api_key(x_api_key: str = Header(...)):
    if not secrets.compare_digest(x_api_key, AI_API_KEY):
        raise HTTPException(status_code=403, detail="Forbidden")

# הוסף dependency לכל endpoint:
@app.post("/predict", dependencies=[Depends(verify_api_key)])
@app.post("/enroll",  dependencies=[Depends(verify_api_key)])
```

**ב-Spring Boot (`application.properties`):**
```properties
ai.api.key=${AI_API_KEY}
```

**ב-`AudioService.java` ו-`UserService.java`** — הוסף header לכל קריאת RestTemplate:
```java
@Value("${ai.api.key}")
private String aiApiKey;

// בכל קריאת HTTP ל-FastAPI:
headers.set("X-Api-Key", aiApiKey);
```

**הפעלה:**
```bash
set AI_API_KEY=<מחרוזת-אקראית-ארוכה>  # Windows
python ai_engine/api.py
```

---

### [תיקון C-4] — העבר JWT Secret למשתנה סביבה

**`application.properties`** — הסר את הסוד הקשיח:
```properties
# לפני (מסוכן):
jwt.secret=a-very-long-and-secure-secret-key-for-my-app-must-be-at-least-32-chars!

# אחרי (בטוח):
jwt.secret=${JWT_SECRET}
```

**הפעלה:**
```bash
set JWT_SECRET=<מחרוזת-אקראית-64-תווים>
mvnw spring-boot:run
```

**ייצור JWT_SECRET חזק (Python):**
```python
import secrets
print(secrets.token_hex(32))  # 64 תווים הקס
```

---

### [תיקון H-1] — הסר ברירות מחדל, אלץ הגדרת Admin ידנית

**`DemoApplication.java`** — הסר את ה-bootstrap של admin/admin123:
```java
// הסר:
adminService.createAdmin("admin", "admin123");

// החלף ב-exception שמונע הפעלת שרת ללא admin:
if (adminRepository.count() == 0) {
    throw new IllegalStateException(
        "No admin configured. Set ADMIN_USERNAME and ADMIN_PASSWORD env vars."
    );
}
```

**הפעלה ראשונה:**
```bash
set ADMIN_USERNAME=myAdminName
set ADMIN_PASSWORD=<סיסמה-חזקה>
mvnw spring-boot:run
```

**בנוסף — הוסף Rate Limiting על /api/admin/login:**
```java
// ב-AdminController — map פשוט של ניסיונות (מספיק ל-demo):
private final Map<String, Integer> loginAttempts = new ConcurrentHashMap<>();

@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest req, 
                                           HttpServletRequest httpReq) {
    String ip = httpReq.getRemoteAddr();
    int attempts = loginAttempts.getOrDefault(ip, 0);
    if (attempts >= 5) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(new AuthResponse(false, null, "Too many attempts. Try later."));
    }
    try {
        String token = adminService.authenticate(req.username(), req.password());
        loginAttempts.remove(ip);
        return ResponseEntity.ok(new AuthResponse(true, token, "OK"));
    } catch (BadCredentialsException e) {
        loginAttempts.put(ip, attempts + 1);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new AuthResponse(false, null, "Invalid credentials"));
    }
}
```

---

### [תיקון C-1 נוסף] — אמת embeddings בצד FastAPI (הגנה נוספת)

גם אחרי הוספת API key, בצע ולידציה על ה-embeddings שמגיעים מ-Java:

```python
# ב-api.py, הוסף ב-/predict אחרי json.loads:
for username, emb_list in candidates_raw.items():
    if not isinstance(emb_list, list) or len(emb_list) != 192:
        print(f"⚠️ Rejected malformed embedding for {username}")
        continue
    if not all(isinstance(v, (int, float)) for v in emb_list):
        continue
    vec = torch.tensor(emb_list, dtype=torch.float32)
    candidates[username] = F.normalize(vec, p=2, dim=0)
```

---

### [תיקון H-2] — מנע Replay Attack עם Nonce

**ב-React `api.js`** — הוסף nonce לכל בקשת אודיו:
```javascript
import { v4 as uuidv4 } from 'uuid';

// בבקשת uploadAudio:
formData.append('nonce', uuidv4());
formData.append('timestamp', Date.now().toString());
```

**ב-`AudioController.java`:**
```java
@PostMapping("/upload")
public ResponseEntity<?> uploadFile(
        @RequestParam("file")      MultipartFile file,
        @RequestParam("roomId")    int roomId,
        @RequestParam("nonce")     String nonce,
        @RequestParam("timestamp") long timestamp) {

    // בדוק timestamp — דחה בקשות ישנות מ-30 שניות
    if (Math.abs(System.currentTimeMillis() - timestamp) > 30_000) {
        return ResponseEntity.badRequest()
            .body(new AccessResponse(false, "unknown", 0, "Request expired"));
    }
    // ... שאר הלוגיקה
}
```

---

### [תיקון H-4] — הצפן embeddings ב-MySQL

**`application.properties`:**
```properties
embedding.encryption.key=${EMBEDDING_KEY}
```

**שירות הצפנה פשוט ב-Java:**
```java
@Service
public class EmbeddingEncryptionService {
    @Value("${embedding.encryption.key}")
    private String key;

    public String encrypt(String plainJson) {
        // AES-256 encryption
        // ...
    }
    public String decrypt(String cipherText) {
        // ...
    }
}
```

**אלטרנטיבה פשוטה יותר:** השתמש ב-MySQL column encryption:
```sql
ALTER TABLE users
  MODIFY biometric_embedding VARBINARY(8192);
```

---

### [תיקון H-5] — הגבל reset-password לבעלים בלבד

**`AdminController.java`:**
```java
@PostMapping("/reset-password")
public ResponseEntity<BasicResponse> resetPassword(
        @RequestBody ResetPasswordRequest request,
        Authentication authentication) {  // מחולץ אוטומטית מה-SecurityContext
    
    // אפשר איפוס רק עבור עצמך
    if (!authentication.getName().equals(request.username())) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new BasicResponse(false, "Cannot reset another admin's password"));
    }
    // ...
}
```

---

### [תיקון M-1] — CORS מוגבל

**`SecurityConfig.java`:**
```java
// לפני (מסוכן):
configuration.setAllowedOriginPatterns(List.of("*"));

// אחרי (בטוח):
configuration.setAllowedOrigins(List.of("http://localhost:3000"));
// בייצור: configuration.setAllowedOrigins(List.of("https://yourdomain.com"));
```

---

### [תיקון M-5] — השלם את AccessLog עם IP ו-ProcessingTime

**`AudioService.java` — שנה את שמירת הלוג:**
```java
// קבל IP מה-Request (העבר כפרמטר מ-AudioController)
String ip = request.getRemoteAddr();
long processingTime = System.currentTimeMillis() - t0;

AccessLog log = new AccessLog(filename, detectedName, confidence, 
                               decision.approved(), roomId);
log.setIpAddress(ip);
log.setProcessingTimeMs(processingTime);
accessLogRepository.save(log);
```

---

### [תיקון L-1] — Rate Limiting על /api/audio/upload

```java
// ב-AudioController:
private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

@PostMapping("/upload")
public ResponseEntity<?> uploadFile(..., HttpServletRequest httpReq) {
    String ip = httpReq.getRemoteAddr();
    long now = System.currentTimeMillis();
    Long last = lastRequestTime.get(ip);
    
    if (last != null && now - last < 2_000) {  // מינימום 2 שניות בין ניסיונות
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(new AccessResponse(false, "unknown", 0, "Please wait before trying again"));
    }
    lastRequestTime.put(ip, now);
    // ... שאר הלוגיקה
}
```

---

## סדר עדיפויות לביצוע

| # | תיקון | פגיעות | זמן משוער |
|---|-------|---------|-----------|
| 1 | הוסף API Key ל-FastAPI | C-1, C-2, C-3 | 1 שעה |
| 2 | העבר JWT Secret למשתנה סביבה | C-4 | 15 דקות |
| 3 | הסר ברירות מחדל admin/admin123 | H-1 | 30 דקות |
| 4 | הגבל CORS לכתובת ספציפית | M-1 | 5 דקות |
| 5 | ולידציה על מימד embeddings | M-2 | 15 דקות |
| 6 | הגבל reset-password לבעלים | H-5 | 20 דקות |
| 7 | Rate limiting על login ו-upload | H-1, L-1 | 45 דקות |
| 8 | הוסף Nonce/timestamp לאודיו | H-2 | 1 שעה |
| 9 | השלם AccessLog עם IP | M-5 | 30 דקות |
| 10 | הצפן embeddings ב-MySQL | H-4 | 2 שעות |

**תיקונים 1-4 קריטיים ומהירים — בצע אותם ראשונים.**
