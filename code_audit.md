# Code Audit — SpeakerAuth
## בדיקת קוד בפועל: 5 שאלות עם הפניות מדויקות לקובץ+שורה

---

## 1. איזה מודל נטען ב-api.py בזמן ריצה?

**`api.py` משתמש בהגדרה שלו עצמו — inline, לא מיובא.**

- `ai_engine/api.py:129` — `class ECAPA_TDNN(nn.Module)` מוגדר ישירות בקובץ
- `ai_engine/api.py:168` — `model = ECAPA_TDNN(C=512, emb=192, scale=8)` — מייצר מה-class הזה
- `ai_engine/api.py:320` — `extract_embedding()` מוגדר inline, קורא ל-`model(segs)` (שורה 324)
- `ai_engine/api.py:14-33` — **אין שום `import` מ-`core.model`, `core.pipeline`, או `speaker_id_inference`**

`Speaker1DCNN` — לא קיים בכלל בריפו.

---

## 2. כמה הגדרות `class ECAPA_TDNN` קיימות — ואיזו בשימוש?

**4 הגדרות קוד, רק אחת רצה:**

| קובץ | שורה | בשימוש ב-Runtime? |
|------|------|-------------------|
| `ai_engine/api.py` | 129 | ✅ **כן — זו שרצה** |
| `ai_engine/core/model.py` | 163 | ❌ לא מיובא לשום מקום שרץ |
| `ai_engine/speaker_id_inference.py` | 106 | ❌ קובץ עצמאי שלא מופעל מ-api.py |
| `project_architecture_dump.txt` | 171 | ❌ טקסט בלבד |

### השוואת ההגדרות — פרמטרים תואמים? חלקית:

**`api.py:129` (זו שרצה):**
```
stem = Sequential(Conv1d, BN, ReLU)          ← ReLU בתוך Sequential
SERes2Block(C, dil=2, scale=8)               ← dilation hardcoded 2,3,4
AttentivePool: tanh() → attn                 ← api.py:124
```

**`core/model.py:163` (מת, לא מיובא):**
```
stem = Sequential(Conv1d, BN)                ← ללא ReLU בתוך Sequential (מופעל ב-forward שורה 205)
SERes2Block(C, scale=8)                      ← אין dilation בכלל
AttentivePool: relu() → attn                 ← core/model.py:150
```

**`speaker_id_inference.py:106` (מת):**
```
layer names: b1, b2, b3 / mfa / pool / bn_p / fc / bn_e
```
שמות שכבות שונים לגמרי → `load_state_dict` היה נכשל עליה.

**לגבי `C=512, scale=8, emb=192, dilations=[2,3,4]`:**
- C=512 ✅ — `api.py:168`
- scale=8 ✅ — `api.py:129`
- emb=192 ✅ — `api.py:129`
- dilations=[2,3,4] ✅ — hardcoded ב-`api.py:135-137`

`core/model.py` לעומת זאת — **ללא dilation** (`model.py:105` — `SERes2Block(C, scale)`, הפרמטר dilation לא קיים כלל).

---

## 3. VAD Wiring — האם `core/pipeline.py` מחובר?

**`core/pipeline.py` לא קיים.** ה-git status מראה `AD` (Added→Deleted). במקומו יש `pipeline_archived.py` בלבד.

שרשרת הקריאות **האמיתית** מ-`/enroll`:

```
api.py:330  enroll_speaker()
    └─→ api.py:337   convert_to_wav_mono_16khz(file)    ← FFmpeg
    └─→ api.py:341   extract_embedding(wav_path)
            └─→ api.py:321   _audio_to_tensor(audio_src)
                    └─→ api.py:289   _apply_vad(audio)
                            └─→ api.py:264   _vad_timestamps(audio_t, _vad_model, ...)
                    └─→ api.py:311   _spk_feat.extract(seg)    ← C++ Fbank
    └─→ api.py:324   model(segs)                        ← ECAPA_TDNN.forward()
```

**הכל מוגדר ב-`api.py` עצמו** — שורות 255, 280, 320.
`core/pipeline.py` הוא dead code (נמחק).
`speaker_id_inference.py` גם כן — לא מיובא ולא נקרא מ-api.py.

---

## 4. אימות מנהל — AES-256-GCM או BCrypt?

**BCrypt בלבד. אין AES-256-GCM בשום מקום בריפו.**

**React (צד לקוח):**
- `client/src/pages/LoginPage.jsx:53` — `const result = await login(username, password)` — שולח ישר לשרת
- `client/src/context/AdminAuthContext.jsx:23` — `loginAdmin(username, password)` — קורא ל:
- `client/src/services/api.js:19-20` — `apiClient.post('/admin/login', { username, password })` — plain JSON

אין השוואה ב-React, אין hardcoded ערכים, אין הצפנה בצד לקוח.

**Java (צד שרת):**
- `server/.../controllers/AdminController.java:51-63` — מקבל `@RequestBody LoginRequest`, קורא ל-`adminService.authenticate()`
- `server/.../services/AdminService.java:68` — `passwordEncoder.matches(rawPassword, admin.getPasswordHash())`
- `server/.../config/SecurityConfig.java:71-73` — `@Bean PasswordEncoder` מחזיר `new BCryptPasswordEncoder()`

הסיסמה שהמשתמש מקליד עוברת ב-HTTPS (localhost: HTTP בלבד), מגיעה ל-Java כטקסט גולמי, ומשווה מול BCrypt hash שמאוחסן ב-`admins.password_hash`.

---

## 5. מחיקת User — מה קורה ל-RoomPermissions?

**אין endpoint מחיקה בכלל.**

- `server/.../controllers/UserController.java` — **אין אף מתודה עם `@DeleteMapping`**
  (grep על "delete/Delete" בקובץ החזיר אפס תוצאות)
- `client/src/pages/AdminPage.jsx` — **אין כפתור "הסרת עובד"**
  (grep על "הסר/מחק/delete/remove" החזיר אפס תוצאות)
- `client/src/services/api.js` — אין `deleteUser` / `removeUser`

מה שיש: `PUT /api/users/{id}/toggle` (`UserController.java:48`) — רק מחליף `isAuthorized`. לא מוחק.

**לכן השאלה על RoomPermissions "יתומים" אינה רלוונטית כרגע — פשוט לא ניתן למחוק User.**

### אזהרה לעתיד: אם תוסיפי מחיקה

`server/.../repositories/RoomPermissionRepository.java:9` — ה-Repository מוגדר על `String username`, לא על `@ManyToOne User`. כלומר:
- אין FK constraint בין `room_permissions.username` לטבלת `users`
- מחיקת User **לא** תגרום ל-Cascade Delete אוטומטי
- יישארו שורות יתומות בטבלת `room_permissions`

הפתרון הנכון: להוסיף ב-`UserService` קריאה ל-`roomPermissionRepository.deleteByUsername(username)` לפני מחיקת המשתמש (שיטה שכרגע לא קיימת ב-`RoomPermissionRepository`).
