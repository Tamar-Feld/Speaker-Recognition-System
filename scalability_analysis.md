# ניתוח סקלביליות אופקית — SpeakerAuth

**תאריך:** 2026-06-26
**מנתח:** Senior Backend & Systems Architect
**מערכת:** SpeakerAuth — Java Spring Boot + Python FastAPI + MySQL

---

## הסבר מושגי — "למה אי אפשר פשוט להעתיק את השרת?"

דמיין מסעדה. כל מלצר (שרת) נושא בכיס פתק עם שמות הלקוחות שכבר הזמינו. אם לקוח פנה למלצר 1 בהזמנה, ואז פנה למלצר 2 לשאול "מה הזמנתי?", מלצר 2 לא יודע — הפתק רק אצל מלצר 1.

**הבעיה** היא State מקומי: מידע ששרת אחד יודע ושאר השרתים אינם יודעים. ב-SpeakerAuth יש כמה גורמים כאלה שיפורטו להלן.

---

## מצב הסקלביליות לפי שכבה

### שכבה 1: Java Spring Boot

#### מה שכבר עובד טוב ✅

**JWT הוא Stateless במהותו.** כל instance של Java יכול לאמת טוקן בעצמו כי הוא חותם ומאמת מול המפתח הסודי בלבד — אין צורך ב-session store משותף. כל 5 עותקי Java יכולים לאמת את אותו טוקן.

**אין Session HttpServlet.** הגדרת `SessionCreationPolicy.STATELESS` בוטחת.

**אין in-memory cache ב-Services.** כל הנתונים נשאבים מ-MySQL בכל בקשה.

---

#### בעיה 1: `ai.server.url=http://127.0.0.1:8000` — קריטי לריבוי Java ❌

```properties
# application.properties — כרגע:
ai.server.url=http://127.0.0.1:8000/predict
ai.enroll.url=http://127.0.0.1:8000/enroll
```

`127.0.0.1` = localhost של **כל שרת בנפרד**. אם תרים 5 Java instances, כל אחד ידבר רק עם ה-Python שרץ **על אותה המכונה שלו**. שרת Java שנמצא על מכונה בלי Python יקרוס עם Connection Refused.

**דרוש:** URL של Load Balancer שמפנה לכל ה-Python instances.

---

#### בעיה 2: HikariCP Connection Pool — ברירת מחדל = 10 חיבורים ❌

אין שום הגדרת connection pool ב-`application.properties`. Spring Boot מפעיל HikariCP עם ברירות מחדל:

```
Maximum Pool Size: 10 connections
Minimum Idle:      10
Connection Timeout: 30,000ms
```

Tomcat מגדיר 200 worker threads כברירת מחדל. תחת עומס:

```
200 בקשות במקביל
└── כל בקשה צריכה DB connection
└── HikariCP מחזיק 10 connections
└── 190 threads עומדים בתור ומחכים לחיבור פנוי
└── עיכוב מצטבר → Timeout exceptions → 500 errors
```

---

#### בעיה 3: `SELECT * FROM users` בכל בקשת predict ❌

```java
// AudioService.collectEnrolledEmbeddingsAsJson() — נקראת בכל /predict:
for (User u : userRepository.findAll()) {  // ← SELECT * FROM users בכל קריאה
    ...
    List<Double> emb = objectMapper.readValue(raw, new TypeReference<>() {});
    embeddingsMap.put(u.getUsername(), emb);
}
return objectMapper.writeValueAsString(embeddingsMap);  // ← סריאליזציה מחדש
```

כל בקשת זיהוי קול:
1. מושכת **את כל טבלת המשתמשים** מ-MySQL
2. מפענחת **את כל ה-embeddings** (192 × 8 bytes × N משתמשים)
3. מסדרת אותם לJSON ענקי ושולחת ל-Python

עם 100 משתמשים ו-50 בקשות מקביל = 5,000 full-table scans בשנייה.
עם 1,000 משתמשים = 50,000 DB reads בשנייה. **זה צוואר הבקבוק הגדול ביותר במערכת.**

---

#### בעיה 4: Race Condition ב-Bootstrap ❌

```java
// DemoApplication — נריץ 5 Java instances שמתחילים בו-זמנית:
if (userRepository.count() == 0) {           // Thread A: count=0 ✓
    userRepository.save(new User("tamar"));  // Thread A: INSERT
}                                             // Thread B: count=0 ✓ (לפני commit!)
                                              // Thread B: INSERT → DUPLICATE KEY ERROR ❌
```

חמשת השרתים עולים במקביל, כולם רואים count=0, כולם מנסים להכניס את "tamar". ארבעה מהם יקבלו `DataIntegrityViolationException`.

---

### שכבה 2: Python FastAPI

#### בעיה 5: Single Process — הבעיה הגדולה ביותר ב-Python ❌

```python
# api.py — שורה 438-439:
if __name__ == "__main__":
    uvicorn.run("api:app", host="127.0.0.1", port=8000, reload=False)
    # ← process אחד! worker אחד!
```

ECAPA-TDNN inference היא פעולה **CPU-bound כבדה**. Python GIL (Global Interpreter Lock) מאפשר רק thread פייתון אחד לרוץ בכל רגע נתון.

```
בקשת predict מ-User A: VAD → FFbank → ECAPA inference = ~2-5 שניות (CPU)
בקשת predict מ-User B: ממתינה...
בקשת predict מ-User C: ממתינה...
...
בקשת predict מ-User Z: ממתינה 25×5 = 125 שניות ❌
```

FastAPI עם `def` (לא `async def`) שולח את הפונקציה ל-thread pool, אבל GIL מבטיח שרק inference אחד רץ בפועל.

---

#### בעיה 6: Silero VAD — Thread Safety בעייתי ❌

```python
# _apply_vad() — נקראת מ-threads מקביל:
timestamps = _vad_timestamps(
    audio_t, _vad_model,   # ← global mutable object
    ...
)
```

`_vad_model` הוא TorchScript LSTM עם **internal state** (h, c vectors). אם שני threads קוראים ל-`_vad_timestamps` בו-זמנית עם אותו `_vad_model`, הם עלולים לקרוא/לכתוב את ה-LSTM state בו-זמנית. GIL מגן מפני crash מוחלט, אבל תוצאות ה-VAD עלולות להיות שגויות.

---

#### מה שכבר עובד ✅

```python
torch.set_num_threads(1)  # מונע PyTorch מלפתוח threads עצמאיים שעוקפים GIL
model.eval()               # inference-only, no gradient computation
torch.no_grad()            # memory efficient
```

ECAPA-TDNN בעצמו (לא VAD) — inference ב-eval mode עם no_grad הוא thread-safe לקריאה.

---

### שכבה 3: MySQL

#### גרסה אחת = Single Point of Truth ✅

MySQL רצה על מכונה אחת עם schema אחד. כל Java instances מתחברים לאותו DB. זה נכון.

#### בעיה 7: `ddl-auto=update` בייצור עם ריבוי instances ❌

```properties
spring.jpa.hibernate.ddl-auto=update
```

אם 5 Java instances עולים בו-זמנית, **5 Hibernate instances מנסים לעדכן את ה-schema בו-זמנית**. Hibernate's DDL update לא thread-safe בין processes. עלול לגרום ל-deadlocks על table locks.

---

## מפת צווארי הבקבוק תחת עומס

```
1,000 בקשות זיהוי בשנייה
         │
    ┌────▼────────────────────────────────────────┐
    │  Java Load Balancer                          │
    └────┬─────────┬─────────┬────────────────────┘
         │         │         │
    Java-1      Java-2    Java-3
         │
    SELECT * FROM users ─────────────► ❌ DB Overload
         │
    serialize 1000 embeddings ────────► ❌ CPU + Memory spike
         │
    POST 127.0.0.1:8000/predict ──────► ❌ localhost only
         │
    ┌────▼──────────┐
    │  Python (1)   │
    │  single proc  │ ────────────────► ❌ GIL bottleneck
    │  ~3s/request  │
    └───────────────┘
```

---

## תוכנית פעולה — לפי עדיפות

### שלב 1: תיקוני קוד מיידיים

#### תיקון 1 — `application.properties`: URL דינמי ל-AI

```properties
# לפני:
ai.server.url=http://127.0.0.1:8000/predict

# אחרי — ערך שניתן לשנות ב-environment variable:
ai.server.url=${AI_ENGINE_URL:http://127.0.0.1:8000/predict}
```

כך בייצור: `export AI_ENGINE_URL=http://ai-loadbalancer:8000/predict`

---

#### תיקון 2 — HikariCP Connection Pool

```properties
# application.properties — הוספה:
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.connection-timeout=20000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=1200000
```

נוסחה: `pool_size = (core_threads × 2) + spindle_disks`. לשרת עם 4 cores: ~10-20 connections.

---

#### תיקון 3 — Tomcat Thread Pool

```properties
# application.properties — הוספה:
server.tomcat.threads.max=150
server.tomcat.threads.min-spare=20
server.tomcat.accept-count=100
```

---

#### תיקון 4 — Bootstrap Race Condition

```java
// DemoApplication.java:
if (userRepository.count() == 0) {
    try {
        userRepository.save(new User("tamar", "tamar", true));
    } catch (DataIntegrityViolationException ignored) {
        // instance אחר כבר יצר — בסדר גמור
        logger.info("Seed user already exists (created by another instance).");
    }
}
if (adminRepository.count() == 0) {
    try {
        adminService.createAdmin("admin", "admin123");
    } catch (IllegalArgumentException ignored) {
        logger.info("Seed admin already exists (created by another instance).");
    }
}
```

---

#### תיקון 5 — `ddl-auto` בייצור

```properties
# application.properties:
spring.jpa.hibernate.ddl-auto=validate
# במעבר לייצור: Flyway/Liquibase מנהלים migrations, לא Hibernate
```

---

#### תיקון 6 — Python: Gunicorn עם Workers מרובים

```bash
# לפני (single process):
python api.py

# אחרי — N workers = N parallel inferences:
gunicorn api:app \
  --worker-class uvicorn.workers.UvicornWorker \
  --workers 4 \
  --bind 0.0.0.0:8000 \
  --timeout 60 \
  --worker-connections 1000
```

כל worker הוא process עצמאי עם PyTorch model משלו ב-RAM.
`workers=4` על מכונה עם 4 cores = 4 inferences מקביל.

---

#### תיקון 7 — Silero VAD Thread Safety

```python
# לפני — global shared model (thread-unsafe):
timestamps = _vad_timestamps(audio_t, _vad_model, ...)

# אחרי — model per-thread באמצעות threading.local():
import threading
_vad_model_local = threading.local()

def _get_vad_model():
    if not hasattr(_vad_model_local, 'model') or _vad_model_local.model is None:
        _vad_path = os.path.join(_AI_ENGINE_DIR, "core", "weights", "silero_vad_offline.jit")
        if os.path.exists(_vad_path):
            _vad_model_local.model = torch.jit.load(_vad_path, map_location="cpu")
            _vad_model_local.model.eval()
        else:
            _vad_model_local.model = None
    return _vad_model_local.model

def _apply_vad(audio: np.ndarray) -> tuple:
    vad = _get_vad_model()
    if vad is None:
        return audio, 1.0
    audio_t    = torch.from_numpy(audio).float()
    timestamps = _vad_timestamps(audio_t, vad, ...)
    if not timestamps:
        return audio, 0.0
    speech_t = _vad_collect(timestamps, audio_t)
    ratio    = len(speech_t) / max(len(audio_t), 1)
    return speech_t.numpy(), float(ratio)
```

---

### שלב 2: Redis לפתרון צוואר הבקבוק המרכזי

**הבעיה:** `SELECT * FROM users` בכל בקשת predict.

**הפתרון:** Cache של ה-embeddings ב-Redis, עם פקיעת תוקף רק בעת Enrollment חדש.

```
בלי Redis:
  כל /predict → SELECT * → 200ms DB query × 1000 req/s = DB overload

עם Redis:
  כל /predict → GET embeddings:all → <1ms cache hit
  רק /enroll  → INVALIDATE embeddings:all → SELECT * (פעם אחת בלבד)
```

```java
// application.properties — הוספה:
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=6379
spring.cache.type=redis
spring.cache.redis.time-to-live=300000  # 5 דקות

// AudioService.java:
@Cacheable(value = "embeddings", key = "'all_authorized'")
private String collectEnrolledEmbeddingsAsJson() throws Exception { ... }

// UserService.java — בסיום registerAndEnroll:
@CacheEvict(value = "embeddings", key = "'all_authorized'")
public String registerAndEnroll(...) { ... }
```

**למה Redis ולא In-Memory Cache?**

```
Java-1: cache[embeddings] = {tamar: [...]}  ← רישם tamar
Java-2: cache[embeddings] = EMPTY           ← עדיין לא יודע על tamar
Java-3: cache[embeddings] = {tamar: [...]}  ← גם הוא יודע

Redis = cache משותף → Java-1, Java-2, Java-3 רואים אותו מידע
```

In-memory cache (כמו `@Cacheable` ללא Redis) ב-5 Java instances = 5 caches שונים שאינם מסונכרנים.

---

### שלב 3: ארכיטקטורת ייצור מלאה

```
                        ┌─────────────────┐
                        │   Load Balancer  │
                        │  (Nginx/AWS ALB) │
                        └────────┬────────┘
                                 │
           ┌─────────────────────┼─────────────────────┐
           │                     │                       │
    ┌──────▼──────┐       ┌──────▼──────┐       ┌──────▼──────┐
    │  Java-1     │       │  Java-2     │       │  Java-3     │
    │  :8080      │       │  :8080      │       │  :8080      │
    └──────┬──────┘       └──────┬──────┘       └──────┬──────┘
           │                     │                       │
           └─────────────────────┼─────────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    │                         │
             ┌──────▼──────┐          ┌───────▼────┐
             │   MySQL     │          │   Redis    │
             │  (Primary + │          │  (Cache)   │
             │  Replica)   │          └────────────┘
             └─────────────┘
                                 │
                        ┌────────▼────────┐
                        │  AI Load Balancer│
                        └────────┬────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                         │
 ┌──────▼──────┐          ┌──────▼──────┐          ┌──────▼──────┐
 │  Python-1   │          │  Python-2   │          │  Python-3   │
 │  4 workers  │          │  4 workers  │          │  4 workers  │
 │  :8000      │          │  :8001      │          │  :8002      │
 └─────────────┘          └─────────────┘          └─────────────┘
```

---

## טבלת פעולות סיכום

| בעיה | חומרה | תיקון מיידי | תיקון ייצור |
|---|---|---|---|
| `127.0.0.1` hardcoded | קריטי | `${AI_ENGINE_URL}` env var | AI Load Balancer |
| `SELECT *` בכל predict | קריטי | — | Redis Cache + `@Cacheable` |
| Python single process | קריטי | Gunicorn 4 workers | 3 מכונות × 4 workers |
| HikariCP pool=10 | גבוה | pool-size=30 | pool-size לפי load test |
| Bootstrap race condition | בינוני | catch `DataIntegrityViolationException` | Flyway migrations |
| Silero VAD thread safety | בינוני | `threading.local()` | per-process load (Gunicorn) |
| `ddl-auto=update` | בינוני | → `validate` | Flyway/Liquibase |
