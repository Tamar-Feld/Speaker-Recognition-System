# תהליך רישום ביומטרי — SpeakerAuth
## פירוט מלא שלב אחר שלב בכל השכבות

---

## סקירה כללית

תהליך הרישום ("enrollment") הוא הפעולה שבה המערכת לומדת לזהות עובד חדש לפי קולו.
בסיום התהליך, ה-DNA הקולי של העובד (וקטור מספרי בן 192 ממדים) מאוחסן במסד הנתונים,
ומאותו רגע — כל דיבור של 4 שניות יאפשר לזהות את זהותו ולהחליט אם לפתוח לו את הדלת.

**שרשרת השירותים המעורבים:**
```
React (Admin Dashboard)
        ↓  POST /api/users/register  [multipart/form-data, Bearer JWT]
Spring Boot (UserController → UserService)
        ↓  POST /enroll  [לכל sample בנפרד]
FastAPI (ai_engine/api.py → ECAPA-TDNN model)
        ↓  192-dim embedding חזרה
Spring Boot (ממצע + מנרמל + שומר ב-MySQL)
        ↓
MySQL — users.biometric_embedding = "[0.123, -0.045, ...]"
```

---

## שלב 0 — מצב התחלתי: שרתים עלו לאוויר

לפני שהאדמין פותח את הדפדפן, שלושת השרתים כבר רצים:

### FastAPI (`ai_engine/api.py`) — עליית מודל לזיכרון RAM

**`load_model_to_ram()`** — פונקציה עם decorator `@app.on_event("startup")`, רצה **פעם אחת** בעת עליית הפייתון:

1. **בניית אדריכלות המודל בזיכרון:**
   ```python
   model = ECAPA_TDNN(C=512, emb=192, scale=8)
   ```
   נבנית רשת נוירונים עם שכבות:
   - `stem` — Conv1d(80→512, kernel=5) + BatchNorm + ReLU
   - `block1`, `block2`, `block3` — שלושה SERes2Block עם Dilations שונים (2,3,4)
   - `mfa_conv` — Multi-scale Feature Aggregation: Conv1d(1536→1536)
   - `pooling` — AttentivePool (Attentive Statistics Pooling)
   - `fc_embed` — Linear(3072→192)
   - `bn_embed` — BatchNorm1d(192)

2. **טעינת משקולות מהדיסק:**
   ```python
   ckpt = torch.load("core/weights/best_model.pt", map_location=device, weights_only=True)
   state = ckpt.get("model", ckpt.get("state_dict", ckpt))
   model.load_state_dict(state)
   ```
   הקובץ `best_model.pt` (24.8MB) נקרא מהדיסק פעם אחת. כל משקולות הרשת עוברות לזיכרון RAM.
   מעכשיו — **אפס גישה לדיסק** לשם inference.

3. **המודל עובר למצב Eval:**
   ```python
   model.to(device)   # CPU (או CUDA אם יש GPU)
   model.eval()       # מכבה Dropout, BatchNorm במצב inference
   ```

4. **טעינת Silero VAD (Voice Activity Detection):**
   ```python
   _vad_model = torch.jit.load("core/weights/silero_vad_offline.jit", map_location="cpu")
   _vad_model.eval()
   ```
   מודל נוסף, קטן וייעודי לזיהוי מקטעי דיבור בתוך שתיקה.
   נשמר במשתנה גלובלי `_vad_model` — גם הוא בזיכרון RAM.

### Spring Boot — עליית שרת ואתחול DB

`DemoApplication.java` → `initDatabase()` רץ כ-`CommandLineRunner` ומוודא שיש מנהל ומשתמש אחד לפחות ב-MySQL.

---

## שלב 1 — ממשק המשתמש: מילוי הטופס (React / AdminPage.jsx)

האדמין נכנס לדשבורד בכתובת `http://localhost:3000/admin`.

### מה רואה האדמין בממשק:
טופס "רישום עובד חדש" עם שלושה שדות:
- `שם משתמש (login ID)` — ה-identifier הייחודי (לדוגמה: `"tamar"`)
- `שם מלא` — שם תצוגה (לדוגמה: `"תמר כהן"`)
- `<input type="file" multiple accept="audio/*">` — בחירת 3+ קבצי שמע

### מצב (State) ב-React:
```javascript
// AdminPage.jsx — שורות 44-48
const [regUsername, setRegUsername] = useState('')   // שם המשתמש
const [regFullName, setRegFullName] = useState('')   // שם מלא
const [regFiles,    setRegFiles]    = useState([])   // File[] — קבצי השמע
const [regBusy,     setRegBusy]     = useState(false) // למניעת לחיצה כפולה
```

כשהאדמין בוחר קבצים, `onChange` ב-input מפעיל:
```javascript
onChange={(e) => setRegFiles(Array.from(e.target.files))}
```
`Array.from(e.target.files)` ממיר `FileList` (Web API) למערך רגיל של אובייקטי `File`.

---

## שלב 2 — לחיצה על "רישום": בניית הבקשה ב-React

`handleRegister(e)` רצה כ-event handler של `onSubmit` על הטופס.

### ולידציה ראשונית (Client-side):
```javascript
// AdminPage.jsx — שורה 95-98
if (!regUsername || !regFullName || regFiles.length < 3) {
    setErrMsg('נדרשים שם משתמש, שם מלא ולפחות 3 הקלטות קוליות')
    return
}
```
בדיקה מהירה שנחסכת מה-server side: לפחות 3 קבצים.

### בניית FormData:
```javascript
// AdminPage.jsx — שורות 101-105
const fd = new FormData()
fd.append('username', regUsername)      // → ?username=tamar
fd.append('fullName', regFullName)      // → ?fullName=תמר כהן
regFiles.forEach((f) => fd.append('samples', f))  // → שלושה שדות "samples"
```

**למה FormData ולא JSON?**
כי קבצי שמע הם binary data — אי אפשר לשלוח אותם כ-JSON. `multipart/form-data` מאפשר לשלב טקסט ובינארי באותה בקשה, כשכל חלק מופרד ב-boundary header.

### שליחת הבקשה דרך Axios:
```javascript
// api.js — שורה 32
export const registerUser = (formData) => apiClient.post('/users/register', formData)
```

**Axios Interceptor** מוסיף אוטומטית את ה-JWT:
```javascript
// api.js — שורות 10-16
apiClient.interceptors.request.use((config) => {
    const token = sessionStorage.getItem('adminToken')
    if (token) {
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})
```

**בקשת HTTP שיוצאת מהדפדפן:**
```
POST http://localhost:8080/api/users/register
Authorization: Bearer eyJhbGci...
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW

------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="username"

tamar
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="fullName"

תמר כהן
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="samples"; filename="sample1.wav"
Content-Type: audio/wav

<binary data...>
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="samples"; filename="sample2.wav"
...
```

---

## שלב 3 — Spring Security: בדיקת JWT

לפני שהבקשה מגיעה ל-Controller, היא עוברת דרך `JwtFilter`.

### `JwtFilter extends OncePerRequestFilter`

**מה בודק הפילטר:**
1. האם יש Header `Authorization: Bearer <token>`?
2. האם ה-token חתום בצורה תקינה ולא פג תוקפו?

**לגבי `/api/users/register`:** כלל `.anyRequest().authenticated()` ב-SecurityConfig גורם לכך שנדרש JWT תקין. JwtFilter מאמת את ה-token ומכניס את הזהות ל-`SecurityContextHolder`.

```java
// SecurityConfig.java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/admin/login").permitAll()
    .requestMatchers("/api/audio/upload").permitAll()
    .requestMatchers("/error").permitAll()
    .anyRequest().authenticated()   // ← /api/users/register דורש אימות
)
```

אם ה-JWT תקין — הבקשה עוברת ל-Controller.

---

## שלב 4 — Spring Boot Controller: קבלת הבקשה

### `UserController.registerUserAndEnroll()`

```java
// UserController.java — שורות 72-96
@PostMapping("/register")
public ResponseEntity<?> registerUserAndEnroll(
        @RequestParam("username")  String         username,
        @RequestParam("fullName")  String         fullName,
        @RequestParam("samples")   MultipartFile[] samples) {
```

**`@RequestParam` vs `@RequestBody`:**
- `@RequestBody` — עבור JSON גולמי
- `@RequestParam("samples")` — עבור `multipart/form-data`. Spring מחלץ כל Part לפי שם השדה.

Spring ממפה את כל שדות ה-`samples` מה-multipart לתוך מערך `MultipartFile[]`. כל `MultipartFile` מכיל:
- `getOriginalFilename()` — שם הקובץ המקורי
- `getBytes()` — תוכן בינארי
- `isEmpty()` — האם הקובץ ריק
- `getContentType()` — MIME type (audio/wav וכו')

**ולידציה ב-Controller:**
```java
if (samples == null || samples.length < 3) {
    return ResponseEntity.badRequest().body(
            Map.of("error", "נדרשות לפחות שלוש הקלטות קוליות לרישום ביומטרי."));
}
```

לאחר מכן מעביר לשירות:
```java
String message = userService.registerAndEnroll(username, fullName, samples);
return ResponseEntity.ok(Map.of("status", "success", "message", message));
```

---

## שלב 5 — UserService: תיאום התהליך הכולל

### `registerAndEnroll()` — הפונקציה המרכזית

```java
// UserService.java — שורה 99
public String registerAndEnroll(String username, String fullName, MultipartFile[] samples) throws Exception
```

**שלושה שלבים מרכזיים:**

### 5א. שליחת כל Sample ל-FastAPI

```java
List<List<Double>> allEmbeddings = enrollSamplesViaAi(samples);
```

מחזיר רשימה של embeddings — אחד לכל sample שעובד בהצלחה.

### 5ב. ממוצע + נירמול

```java
double[] mean = averageAndNormalize(allEmbeddings);
```

### 5ג. שמירה ב-MySQL

```java
persistEmbedding(username, fullName, mean);
```

---

## שלב 6 — UserService: שליחה ל-FastAPI (לולאה על כל Sample)

### `enrollSamplesViaAi()` — קריאה לכל sample בנפרד

```java
// UserService.java — שורה 124
private List<List<Double>> enrollSamplesViaAi(MultipartFile[] samples)
```

**עבור כל sample, Spring Boot מבצע:**

#### 6א. עטיפת הבינארי ב-ByteArrayResource
```java
ByteArrayResource res = new ByteArrayResource(sample.getBytes()) {
    @Override
    public String getFilename() {
        return sample.getOriginalFilename() != null ? sample.getOriginalFilename() : "sample.wav";
    }
};
```
`ByteArrayResource` מאפשר ל-`RestTemplate` לשלוח את הבייטים הגולמיים כ-multipart. חשוב: `getFilename()` חייב להיות overridden — ללא זה FastAPI לא יכול לזהות את ה-Part.

#### 6ב. בניית בקשת multipart
```java
MultiValueMap<String, Object> enrollBody = new LinkedMultiValueMap<>();
enrollBody.add("file", new HttpEntity<>(res, fileHdr));

HttpHeaders enrollHdr = new HttpHeaders();
enrollHdr.setContentType(MediaType.MULTIPART_FORM_DATA);
```

#### 6ג. שליחה ל-FastAPI
```java
ResponseEntity<Map> aiResp = restTemplate.postForEntity(
        "http://127.0.0.1:8000/enroll",
        new HttpEntity<>(enrollBody, enrollHdr),
        Map.class);
```

`RestTemplate` (עם Timeout שהוגדר ב-`DemoApplication`):
- ConnectTimeout: 3 שניות
- ReadTimeout: 30 שניות

#### 6ד. חילוץ ה-embedding מהתשובה
```java
Object rawEmb = aiResp.getBody().get("embedding");
List<Double> emb = objectMapper.convertValue(rawEmb, new TypeReference<>() {});
if (emb.size() != 192) {
    logger.warn("⚠️ Sample returned embedding of size {}, expected 192 — skipped.", emb.size());
} else {
    allEmbeddings.add(emb);
}
```

FastAPI מחזיר JSON: `{"embedding": [0.123, -0.045, ..., 0.087]}` — רשימה של 192 מספרי double.
`ObjectMapper.convertValue()` ממיר את ה-`List<Object>` ל-`List<Double>`.

**טיפול בשגיאות לכל sample:**
```java
catch (HttpClientErrorException e) {
    if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
        // FastAPI החזיר 422 — קובץ עם יותר מדי שתיקה (VAD)
        logger.warn("⚠️ Sample {} rejected — insufficient speech detected (VAD). Skipped.", (i + 1));
    }
}
```

Sample שנדחה לא מוסיף ל-`allEmbeddings` — הוא מדולג ועוברים לבא.

---

## שלב 7 — FastAPI: קבלת Sample ועיבוד ראשוני

### `enroll_speaker()` — endpoint `/enroll`

```python
# api.py — שורה 330
@app.post("/enroll")
def enroll_speaker(file: UploadFile = File(...)):
```

FastAPI מקבל `UploadFile` — wrapper מעל הקובץ הנכנס.

### 7א. המרת פורמט אודיו: `convert_to_wav_mono_16khz()`

```python
wav_path = convert_to_wav_mono_16khz(file)
```

**מה הפונקציה עושה:**

1. **יצירת שמות קבצים ייחודיים (UUID):**
   ```python
   temp_input  = f"raw_audio_{uuid.uuid4().hex}.webm"
   temp_output = f"converted_audio_{uuid.uuid4().hex}.wav"
   ```
   UUID מונע קונפליקטים בין בקשות מקביליות (Windows WinError 32 — file lock).

2. **כתיבת הקובץ הנכנס לדיסק זמני:**
   ```python
   with open(temp_input, "wb") as f:
       f.write(incoming_file.file.read())
   ```
   הקובץ הנכנס יכול להיות WebM, Ogg, MP3, WAV — כל פורמט שהדפדפן או המשתמש שלח.

3. **המרה עם FFmpeg:**
   ```python
   ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
   command = [
       ffmpeg_exe, "-y",
       "-i",  temp_input,
       "-ac", "1",         # mono
       "-ar", "16000",     # 16kHz
       "-loglevel", "error",
       temp_output
   ]
   subprocess.run(command, ...)
   ```
   FFmpeg מבצע:
   - Stereo → Mono: ממצע בין הערוצים
   - Resample: לדוגמה 44.1kHz → 16kHz (הורדת תדירות דגימה)
   - Encoding: → PCM WAV לייניארי (ללא דחיסה)

4. **ניקוי ה-input הזמני מיידית (finally block):**
   ```python
   if os.path.exists(temp_input):
       os.remove(temp_input)
   ```

מוחזר: `wav_path` — נתיב לקובץ WAV זמני ב-16kHz Mono.

---

## שלב 8 — FastAPI: חילוץ Embedding עם ECAPA-TDNN

### `extract_embedding(wav_path)` 

```python
# api.py — שורה 320
def extract_embedding(audio_src, n_segs: int = 5) -> torch.Tensor:
    segs = _audio_to_tensor(audio_src, n_segs=n_segs)
    segs = segs.to(device)
    with torch.no_grad():
        embs = model(segs)
    return embs.mean(0).cpu()
```

מתחת לפני השטח: `_audio_to_tensor()` מכין את הנתונים, ואז `model(segs)` מריץ את הרשת.

---

## שלב 9 — FastAPI: צינור עיבוד האודיו (`_audio_to_tensor()`)

זהו הלב של עיבוד האות הקולי. **9 שלבים ברצף:**

### 9א. קריאת WAV עם soundfile
```python
audio, sr = _sf.read(audio_src)
```
`audio` הוא `numpy.ndarray` של float64, `sr` הוא sample rate (16000).

### 9ב. Stereo → Mono (אם נדרש)
```python
if audio.ndim > 1:
    audio = audio.mean(axis=1)  # ממוצע בין ערוצים
```
לרוב מיותר (FFmpeg כבר עשה זאת), אבל כ-safety net.

### 9ג. Resampling (אם נדרש)
```python
if sr != 16000:
    audio = librosa.resample(audio.astype(np.float32), orig_sr=sr, target_sr=16000)
```
לרוב מיותר (FFmpeg כבר עשה זאת).

### 9ד. VAD — Voice Activity Detection (`_apply_vad()`)
```python
audio, _ratio = _apply_vad(audio)
if _ratio < SPEECH_RATIO_MIN:  # SPEECH_RATIO_MIN = 0.30
    raise ValueError(f"SPEECH_RATIO_TOO_LOW:{_ratio:.3f}")
```

**מה VAD עושה:**

```python
audio_t    = torch.from_numpy(audio).float()
timestamps = _vad_timestamps(
    audio_t, _vad_model,
    sampling_rate=16000,
    threshold=0.5,            # סף הסתברות לדיבור
    min_silence_duration_ms=100,
    speech_pad_ms=30,
    min_speech_duration_ms=250,
)
```

`_vad_timestamps()` מחזיר רשימה של `{"start": int, "end": int}` — כל זוג הוא מקטע דיבור בסמפלים.

```python
speech_t = _vad_collect(timestamps, audio_t)  # שרשור כל מקטעי הדיבור
ratio    = len(speech_t) / max(len(audio_t), 1)
```

**מה קורה בפועל:**
- קובץ של 4 שניות עם 1 שנייה דיבור → ratio=0.25 → נדחה (422)
- קובץ של 4 שניות עם 2 שניות דיבור → ratio=0.50 → מתקבל

הסיבה: מודל ECAPA-TDNN לא יכול לחלץ מידע ביומטרי משתיקה. הרישום עם שתיקה רבה → embedding לא מייצג.

### 9ה. RMS Normalization (נרמול עוצמה)
```python
_rms = np.sqrt(np.mean(audio ** 2))
if _rms > 1e-6:
    audio = (audio / _rms * 0.1).astype(np.float32)
```

`RMS = √(mean(x²))` — שורש ממוצע ריבועי. מדדי אנרגיה. המטרה: הגעה ל-RMS=0.1 ללא תלות בעוצמת הדיבור המקורית.

**למה זה חשוב:** אם אחד מדבר ברם ואחר בלחישה, ה-embedding יצא שונה מסיבות שאינן קשורות לזהות הקול. נרמול עוצמה מנטרל את ההבדל.

### 9ו. Tiling — השלמת אודיו קצר
```python
if len(audio) < 48000:  # פחות מ-3 שניות
    reps  = math.ceil(48000 / len(audio))
    audio = np.tile(audio, reps)[:48000]
```

אם קובץ השמע קצר מ-3 שניות (48,000 סמפלים ב-16kHz): מחזור החומר הקיים עד להגעה לאורך הנדרש. **לא padding בשתיקה** — חזרה על הדיבור עצמו.

### 9ז. Multi-Segment Extraction (n_segs=5)
```python
n_segs = max(1, min(5, len(audio) // 48000 + 1))
if n_segs == 1:
    starts = [0]
else:
    step   = (len(audio) - 48000) / (n_segs - 1)
    starts = [int(round(i * step)) for i in range(n_segs)]
```

לקובץ ארוך (לדוגמה 10 שניות): לוקחים 5 חתכים שונים, כל אחד באורך 3 שניות, ממיקומים שונים. זה מגדיל רובסטיות — מודל לא נסמך על מקטע אחד.

### 9ח. Fbank Feature Extraction (C++ Module)
```python
for s in starts:
    seg  = audio[s: s + 48000]  # 3 שניות בדיוק
    feat = _spk_feat.extract(np.ascontiguousarray(seg, dtype=np.float32))
```

`_spk_feat` הוא מודול C++ (`speaker_features.pyd`) שמחשב **Filterbank Features (Fbank)**:

**מה Fbank עושה:**
1. חלוקת האות לפריימים של 25ms עם overlapping של 10ms
2. החלת חלון Hamming על כל פריים
3. FFT (Fast Fourier Transform) לכל פריים
4. החלת 80 Mel-filterbanks (דחיסה לסקאלה שדומה לשמיעה האנושית)
5. לוגריתם של האנרגיה בכל filterbank

תוצאה: מטריצה בגודל **[80 × 297]** — 80 ערוצי תדירות × 297 פריימי זמן.

**למה 297?**
`(48000 - 512) / 160 + 1 = 297` — מחושב לפי N_FFT=512, HOP=160.

### 9ט. Z-Score Normalization (נירמול סטטיסטי)
```python
feat = ((feat - feat.mean(1, keepdims=True)) / (feat.std(1, keepdims=True) + 1e-5))
```

עבור כל אחד מ-80 ערוצי התדירות בנפרד: מחסיר ממוצע ומחלק בסטיית תקן.
**למה:** מנטרל הבדלי עוצמה בין ערוצי תדירות שנובעים מתנאי הרקלטה ולא מהקול.

לאחר מכן: padding/trimming ל-_FIXED_T=297 פריימים בדיוק.

תוצאה מ-`_audio_to_tensor()`: **tensor בגודל [5, 80, 297]** — 5 קטעים, כל אחד 80×297.

---

## שלב 10 — FastAPI: הרצת ECAPA-TDNN

### `model(segs)` — Forward Pass

```python
segs = segs.to(device)           # [5, 80, 297]
with torch.no_grad():
    embs = model(segs)           # [5, 192]
return embs.mean(0).cpu()        # [192]
```

**מבנה ה-Forward Pass של ECAPA_TDNN:**

#### שכבה 1: Stem
```python
h = self.stem(x)    # [5, 80, 297] → [5, 512, 297]
```
Conv1d(80→512, kernel=5) + BN + ReLU. מוצא ייצוגים בסיסיים של האות.

#### שכבה 2-4: SERes2Block (×3)

כל בלוק:
```python
o1 = self.block1(h,  residual=h)         # dilation=2
o2 = self.block2(o1, residual=h + o1)    # dilation=3, skip connection מצטבר
o3 = self.block3(o2, residual=h + o1 + o2) # dilation=4
```

בתוך כל SERes2Block:
- **Conv1d(1×1)** — הקרנה
- **Res2Conv1dReluBn** — מפצל לכפולות של 7 convolutions קטנות ומשרשרם (Res2Net style, קולט תבניות בסקאלות זמן שונות)
- **SEBlock1d** — Squeeze-and-Excitation: מחשב אילו ערוצי features חשובים, ומשקלל בהתאם

כל בלוק שומר קובץ [5, 512, 297].

#### שכבה 5: Multi-scale Feature Aggregation
```python
mfa = self.mfa_conv(torch.cat([o1, o2, o3], 1))  # [5, 1536, 297]
```
שרשור שלושת הפלטים → Conv1d(1536→1536). מאגד תבניות מרמות עומק שונות.

#### שכבה 6: Attentive Statistics Pooling
```python
stats = self.pooling(mfa)    # [5, 3072]
```

**הלב של ה"מאזין":**
```python
# AttentivePool.forward()
mu    = x.mean(2, keepdim=True).expand_as(x)    # ממוצע גלובלי
sg    = x.var(2,  keepdim=True).clamp(1e-4).sqrt().expand_as(x)  # סטיית תקן גלובלית
ctx   = torch.cat([x, mu, sg], 1)               # [5, 4608, 297] — הקשר עשיר

alpha = F.softmax(self.attn(torch.tanh(self.tdnn(ctx))), dim=2)  # משקלי תשומת לב לכל פריים
mean  = (alpha * x).sum(2)                      # ממוצע משוקלל לפי תשומת לב
std   = ((alpha * x.pow(2)).sum(2) - mean.pow(2) + 1e-4).sqrt()  # סטיית תקן משוקללת
return torch.cat([mean, std], 1)                # [5, 3072]
```

במקום לממצע נאיבית לאורך הזמן, Attentive Pooling **לומד לתת משקל גבוה יותר לפריימים אינפורמטיביים**. פריים עם רעש רקע מקבל משקל נמוך.

#### שכבה 7: Embedding Layer
```python
emb = self.bn_embed(self.fc_embed(self.bn_pool(stats)))  # [5, 192]
```
- BN(3072) → FC(3072→192) → BN(192)

#### שכבה 8: L2 Normalization
```python
return F.normalize(emb, p=2, dim=1)  # [5, 192], כל וקטור על כדור היחידה
```

`‖emb‖₂ = 1` — כל embedding מוקרן על כדור היחידה ב-192 ממד. זה מאפשר להשתמש ב-Cosine Similarity (שוויון ל-Dot Product בין וקטורים מנורמלים).

#### ממוצע על פני 5 קטעים:
```python
return embs.mean(0).cpu()   # [5, 192] → [192]
```

ממוצע גיאומטרי של 5 embedding על כדור היחידה — ייצוג יציב יותר מקטע בודד.

---

## שלב 11 — FastAPI: נרמול סופי והחזרת Embedding

```python
# api.py — שורות 341-342
emb_raw = extract_embedding(wav_path)       # [192] — ממוצע 5 קטעים
emb     = F.normalize(emb_raw, p=2, dim=0) # נרמול L2 נוסף (הממוצע עלול לסטות)
```

**ניקוי הקובץ הזמני (finally block):**
```python
finally:
    if wav_path and os.path.exists(wav_path):
        try: os.remove(wav_path)
        except OSError: pass
```

**FastAPI החזרת תשובה:**
```python
return {"embedding": emb.tolist()}
```

`emb.tolist()` ממיר PyTorch tensor לרשימת Python — JSON-serializable.

**תשובת HTTP מ-FastAPI:**
```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "embedding": [0.0423, -0.0817, 0.1204, ..., -0.0391]
}
```
192 מספרים בין בערך -0.3 ל-+0.3 (כי ‖emb‖₂ = 1).

---

## שלב 12 — Spring Boot: איסוף Embeddings ואגרגציה

לאחר שכל N samples נשלחו ל-FastAPI וחזרו:

```java
// UserService.java — שורות 107-112
List<List<Double>> allEmbeddings = enrollSamplesViaAi(samples);

if (allEmbeddings.size() < 3) {
    throw new IllegalStateException(
        "רק " + allEmbeddings.size() + " מתוך " + samples.length
        + " הקלטות עובדו בהצלחה. נדרשות לפחות שלוש הקלטות תקינות לרישום.");
}
```

`allEmbeddings` הוא `List<List<Double>>` — כל איבר הוא 192 מספרים (embedding אחד).

### `averageAndNormalize()` — ממוצע ונרמול Java-side

```java
// UserService.java — שורות 180-195
private double[] averageAndNormalize(List<List<Double>> allEmbeddings) {
    double[] mean = new double[192];

    // שלב א': ממוצע מספרי פשוט על פני כל embeddings
    for (List<Double> emb : allEmbeddings) {
        for (int i = 0; i < 192; i++) mean[i] += emb.get(i);
    }
    for (int i = 0; i < 192; i++) mean[i] /= allEmbeddings.size();

    // שלב ב': חישוב Norm אוקלידי
    double norm = 0;
    for (double v : mean) norm += v * v;
    norm = Math.sqrt(norm);

    // שלב ג': חלוקה ב-Norm (L2 Normalization)
    if (norm > 1e-9) {
        for (int i = 0; i < 192; i++) mean[i] /= norm;
    }
    return mean;
}
```

**למה נרמול שני?**
הממוצע של 3 וקטורים מנורמלים הוא **לא** וקטור מנורמל. לדוגמה:
- `v1 = [0.7, 0.7, 0, ...]` → norm=1
- `v2 = [0.5, 0.5, 0.7, ...]` → norm=1
- `mean(v1,v2) = [0.6, 0.6, 0.35, ...]` → norm≈0.88 ≠ 1

נרמול L2 מחדש מחזיר את הוקטור לכדור היחידה.

**תוצאה:** `double[192]` — ה-"prototype" הסופי של המשתמש.

---

## שלב 13 — Spring Boot: שמירה ב-MySQL

### `persistEmbedding()` — `@Transactional`

```java
// UserService.java — שורות 199-215
@Transactional
protected void persistEmbedding(String username, String fullName, double[] mean) throws Exception {

    // א. המרת double[] לרשימה ול-JSON
    List<Double> finalEmbList = new ArrayList<>();
    for (double v : mean) finalEmbList.add(v);
    String embJson = objectMapper.writeValueAsString(finalEmbList);
    // embJson = "[0.0423,-0.0817,0.1204,...,-0.0391]"
```

**`objectMapper.writeValueAsString()`** — Jackson ממיר `List<Double>` ל-JSON string בן כ-1,800 תווים.

```java
    // ב. מציאת/יצירת משתמש
    String displayName = (fullName != null && !fullName.isBlank()) ? fullName : username;
    User enrolled = userRepository.findByUsername(username)
            .orElseGet(() -> new User(username, displayName, true));
```

**`orElseGet()`** — אם המשתמש כבר קיים ב-DB (נרשם קודם) — משתמש בו.
אם לא — יוצר חדש עם `isAuthorized=true` (ברירת מחדל: מאושר).

```java
    // ג. עדכון שדות
    if (fullName != null && !fullName.isBlank()) {
        enrolled.setFullName(displayName);
    }
    enrolled.setBiometricEmbedding(embJson);   // מחרוזת JSON של 192 מספרים

    // ד. שמירה ב-MySQL דרך Hibernate
    userRepository.save(enrolled);
}
```

**`userRepository.save()`** — Spring Data JPA:
- אם `enrolled.getId() == null` (חדש) → `INSERT INTO users (...)`
- אם `enrolled.getId() != null` (קיים) → `UPDATE users SET ... WHERE id=?`

**SQL שנוצר ב-MySQL:**
```sql
-- משתמש חדש:
INSERT INTO users (username, full_name, is_authorized, created_at, biometric_embedding)
VALUES ('tamar', 'תמר כהן', 1, '2026-06-27 10:30:00', '[0.0423,-0.0817,...,-0.0391]');

-- או עדכון קיים:
UPDATE users
SET biometric_embedding = '[0.0423,-0.0817,...,-0.0391]', full_name = 'תמר כהן'
WHERE id = 5;
```

`biometric_embedding` הוא עמודת `TEXT` ב-MySQL — מחזיקה עד 65,535 תווים (הרבה מעל ה-~1,800 שאנו צריכים).

---

## שלב 14 — Spring Boot: תשובה ל-React

```java
// UserController.java — שורה 85
return ResponseEntity.ok(Map.of("status", "success", "message", message));
```

**תשובת HTTP:**
```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "success",
  "message": "User 'tamar' enrolled successfully using 3 valid samples."
}
```

---

## שלב 15 — React: עדכון הממשק

```javascript
// AdminPage.jsx — שורות 106-108
await registerUser(fd)
setRegUsername(''); setRegFullName(''); setRegFiles([])  // ניקוי הטופס
loadAll()  // רענון רשימת המשתמשים מהשרת
```

`loadAll()` מושך מחדש את כל המשתמשים מ-`GET /api/users`. המשתמש החדש מופיע בטבלה עם:
- שם מלא ושם משתמש
- סטטוס "מאושר" (כפתור ירוק)
- אפס הרשאות חדרים (ניתן להוסיף ידנית)

---

## סיכום זרימת הנתונים

```
[AdminPage.jsx]
handleRegister() → new FormData() + 3 File objects
        ↓
[api.js]  apiClient.post('/users/register', formData)
        ↓  Authorization: Bearer <JWT>
[Spring Security]  JwtFilter → validates token
        ↓
[UserController.java]
registerUserAndEnroll(@RequestParam MultipartFile[] samples)
        ↓
[UserService.java] registerAndEnroll()
        │
        ├─ enrollSamplesViaAi(samples)          ← לולאה: sample 1
        │         ↓
        │   [RestTemplate]  POST http://127.0.0.1:8000/enroll
        │         ↓
        │   [FastAPI /enroll]
        │     convert_to_wav_mono_16khz()       ← FFmpeg: any format → WAV 16kHz mono
        │     extract_embedding(wav_path)
        │       _audio_to_tensor()
        │         soundfile.read()              ← WAV → numpy float32
        │         _apply_vad()                  ← Silero VAD: strip silence
        │         RMS normalization             ← עוצמה אחידה
        │         np.tile() / crop              ← 3s exact (48,000 samples)
        │         _spk_feat.extract()           ← C++ Fbank: [80×297]
        │         z-score normalization         ← נרמול סטטיסטי לכל ערוץ
        │       model(segs)                     ← ECAPA-TDNN forward: [192]
        │       F.normalize()                   ← L2 normalize → כדור היחידה
        │         ↓  {"embedding": [192 doubles]}
        │   [Spring Boot] → allEmbeddings.add(emb)
        │
        ├─ enrollSamplesViaAi(samples)          ← חזרה על sample 2, 3, ...
        │
        ├─ averageAndNormalize(allEmbeddings)   ← ממוצע + L2 normalize
        │         ↓  double[192] = prototype
        │
        └─ persistEmbedding(username, ..., mean)
                  │
                  ├─ objectMapper.writeValueAsString()  ← JSON string
                  └─ userRepository.save(user)          ← MySQL INSERT/UPDATE
                              ↓
                  users.biometric_embedding = "[0.042,...]"

[UserController] → ResponseEntity 200 OK
        ↓
[AdminPage.jsx] → ניקוי טופס + רענון רשימה
```

---

## גורמי כישלון אפשריים בתהליך

| גורם | שלב | תגובה |
|------|-----|--------|
| פחות מ-3 קבצים | שלב 2 (React) | הודעת שגיאה בממשק |
| JWT לא תקין / פג | שלב 3 | 401 Unauthorized |
| FastAPI לא רץ | שלב 6 | `ConnectTimeout` אחרי 3 שניות, 500 |
| קובץ שמע ריק | שלב 6 | מדולג (empty() check) |
| ≥70% שתיקה ב-sample | שלב 9ד (VAD) | 422 UNPROCESSABLE, sample מדולג |
| FFmpeg נכשל | שלב 7א | 400 מ-FastAPI |
| פחות מ-3 samples עברו VAD | שלב 12 | 422 מ-Spring Boot |
| username כבר קיים + DB constraint | שלב 13 | IllegalArgumentException, 400 |
| MySQL לא רץ | שלב 13 | 500 Internal Server Error |

---

## תכונות אדריכליות מרכזיות

1. **Stateless Python** — FastAPI לא שומר כלום בין קריאות. כל embedding מחושב ומוחזר מיד. המצב (state) יושב ב-MySQL בלבד.

2. **Zero Disk I/O לאורך זמן** — הקבצים הזמניים של FFmpeg נמחקים מיידית ב-`finally` block אחרי כל sample.

3. **חוסן VAD** — sample גרוע לא מפיל את כל הרישום. הוא מדולג, והרישום ממשיך עם הנותרים.

4. **Double L2 Normalization** — אחד ב-FastAPI (לכל sample), שני ב-Java (לפרוטוטייפ המאוחסן). מבטיח ש-Cosine Similarity ב-predict פועל נכון.

5. **Transaction רק סביב DB** — `@Transactional` מוגדר רק על `persistEmbedding()`, לא על `registerAndEnroll()` כולה. כך ה-DB connection לא מוחזק פתוח בזמן המתנה ל-FastAPI (שיכולה לקחת עד 30 שניות).
