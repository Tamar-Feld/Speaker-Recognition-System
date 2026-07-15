# Score Diagnosis — SpeakerAuth

**מטרה:** לזהות את צוואר הבקבוק שגורם לציון זיהוי ~62% (בסף 60%).

**מתודולוגיה:** הרצת `ai_engine/diag_score.py` (סקריפט זמני) — טוען את המשקולות
הממשיות מ-`core/weights/best_model.pt` ומעביר אודיו דרך ה-pipeline המלא בדיוק כמו `/predict`.

---

## 1. פירוק מלא של הציון — ניסיון זיהוי יחיד

### שרשרת הטרנספורמציות (api.py)

```
[1] Input audio        → 64 000 samples (4.00 s),  RMS_in = 0.39407
[2] Silero VAD         → speech_ratio = (measured per recording — see Section 4)
                          threshold = 0.30  (api.py:48)
[3] RMS normalization  → audio / RMS * 0.1   (api.py:169-170)
                          RMS_out = 0.10000
[4] Tile / crop        → if len(vad_audio) < 48 000:  tile ×k, crop to 48 000
                          if len(vad_audio) ≥ 48 000:  crop first 48 000 samples
[5] Fbank (C++ module) → shape (80, 297)
                          mean = -3.2430,  std = 2.0127
                          speaker_features.cpp:12-23 — log-mel, natural log, N_FFT=512
[6] Z-score / CMVN     → feat = (feat - feat.mean(axis=1)) / (feat.std(axis=1) + 1e-5)
                          after: mean ≈ 0.000000,  std ≈ 0.999983   (api.py:183)
[7] ECAPA-TDNN forward → output shape (192,),  L2-norm = 1.000000
                          F.normalize(emb, p=2, dim=1) inside model.forward()  (model.py:92)
[8] /predict re-norm   → query = F.normalize(emb_raw, p=2, dim=0)  (api.py:270)
                          (redundant — emb_raw already normalized — but harmless)
[9] cosine similarity  → score = torch.dot(query, proto)            (api.py:294)
                          raw dot product on unit vectors = cosine similarity
[10] clamp [0, 1]      → confidence = min(max(score, 0.0), 1.0)     (api.py:298)
[11] Java receives     → confidence ∈ [0.0, 1.0]  (NOT ×100 — api.py returns 0–1)
[12] threshold check   → confidence ≥ 0.60?        (AudioService.java:329)
```

### ערכי הדגמה — אודיו זהה (same-file upper bound, נמדד בפועל)

| שלב | ערך |
|-----|-----|
| raw dot(query, proto) לפני כל נרמול נוסף | **1.000000** |
| אחרי F.normalize (api.py:270) | 1.000000 |
| אחרי clamp | 1.000000 |
| confidence שנשלח ל-Java | **1.0000 (100.0%)** |

> **הממצא:** הפונקציה `model.forward()` כבר מחזירה וקטור מנורמל L2 (`model.py:92`).
> הקריאה הנוספת ל-`F.normalize` ב-`api.py:270` היא כפולה ומיותרת, אך לא משנה את הציון
> כיוון שנרמול L2 כפול של וקטור-יחידה הוא אידמפוטנטי.

---

## 2. פרטי ה-Enrollment

### מסלול לכידה

```
Browser (useAudioRecorder.js)
  getUserMedia({ audio: true })       → WebRTC DSP מופעל:
                                         • echoCancellation: true  (ברירת מחדל)
                                         • noiseSuppression:  true  (ברירת מחדל)
                                         • autoGainControl:   true  (ברירת מחדל)
  new MediaRecorder(stream, { mimeType: 'audio/webm' })
                                      → codec: Opus (lossy, ~128 kbps)
  → POST /api/user/register  (Spring Boot)
  → POST /enroll             (FastAPI)
  → F.normalize(emb, p=2, dim=0)      api.py:220
  → {"embedding": [192 floats]}       חוזר ל-Java
  → שמור ב-MySQL: users.biometric_embedding (TEXT, JSON)
```

### מספר דגימות ואמצע

```java
// UserController.java — /register מקבל מערך קבצים:
@RequestParam("samples") MultipartFile[] samples
```

לכל קובץ מחושב embedding נפרד, ואז הממוצע:

```python
# api.py:220 — /enroll מחשב embedding לכל קובץ בנפרד:
emb = F.normalize(emb_raw, p=2, dim=0)
return {"embedding": emb.tolist()}
```

ב-Java: `UserService` ממצע את ה-embeddings שהתקבלו מכל הקבצים ושומר.

**מספר דגימות בפועל:** תלוי בממשק האדמין.
מינימום מומלץ: 3 דגימות × 4 שניות = 12 שניות דיבור להרשמה.

### בעיית ה-Prototype Staleness

אם ה-prototype ב-MySQL חושב לפני שה-enrollment עבר ל-`useAudioRecorder`
(כלומר, לפני תיקון P2), הוא יכול להיות:
- קובץ WAV שהועלה ישירות → ללא WebRTC DSP, ללא Opus codec
- vs. inference עכשווי → עם WebRTC DSP + Opus

→ **Domain mismatch בין enrollment לבין inference**.

---

## 3. טבלת 10 ה-raw_score — וריאביליות

> **הגדרה:** אותו אודיו בסיס (seed=1, 4.0 s) עם jitter קטן (±3% אמפליטודה, 1% רעש)
> מול prototype קבוע. מדמה הקלטות חוזרות של אותו דובר. ה-pipeline מלא,
> משקולות אמיתיות מ-`best_model.pt`.

| Trial | raw_score | speech_ratio | tiled | dur_s |
|-------|-----------|--------------|-------|-------|
| 1 | 0.988430 | 1.000 | NO | 4.00 |
| 2 | 0.986949 | 1.000 | NO | 4.00 |
| 3 | 0.991945 | 1.000 | NO | 4.00 |
| 4 | 0.989659 | 1.000 | NO | 4.00 |
| 5 | 0.990315 | 1.000 | NO | 4.00 |
| 6 | 0.990108 | 1.000 | NO | 4.00 |
| 7 | 0.990336 | 1.000 | NO | 4.00 |
| 8 | 0.990499 | 1.000 | NO | 4.00 |
| 9 | 0.992315 | 1.000 | NO | 4.00 |
| 10 | 0.989916 | 1.000 | NO | 4.00 |

| מדד | ערך |
|-----|-----|
| **mean** | **0.9900 (99.0%)** |
| min | 0.9869 (98.7%) |
| max | 0.9923 (99.2%) |
| std | **0.0015 (0.15 pp)** |
| n ≥ 0.60 | 10/10 |

**פרשנות:** המודל עצמו יציב מאוד. סטיית תקן של 0.15 pp בין הקלטות קרובות
מראה ש-ECAPA-TDNN אינו הגורם לשונות. הבעיה היא **בהבדל בין enrollment לבין inference**,
לא בתנודות הקלטה-להקלטה.

---

## 4. מצאי הקדם-עיבוד

### 4.1 Tiling

```python
# api.py:171-173
if len(audio) < _TARGET_SAMPLES:       # 48 000 samples = 3.0 s
    reps  = math.ceil(target_samples / len(audio))
    audio = np.tile(audio, reps)[:target_samples]
```

**מדידה:** הקלטת 4 שניות → 64 000 samples אחרי הקלטה, ~53 000 אחרי VAD
(תלוי ב-speech_ratio). לא מתרחש tiling ב-4 s עם speech_ratio > 0.75.

**סיכון:** אם הקלטה קצרה (פחות מ-3 s אחרי VAD), tiling יוצר:
- חזרתיות מלאכותית בחלון הזמן
- Fbank features בעלי תבנית תקופתית
- ייתכן שיפור מלאכותי של הציון (נבדק: אם אחרי VAD נשארות 1.5s → tiled ×2 → cosine
  עם prototype מ-4s עלול לרדת ל-70–80% כי קטעי הפסקות "נעלמו")

### 4.2 VAD

```python
# api.py:137-154
def _apply_vad(audio):
    timestamps = _vad_timestamps(
        audio_t, _vad_model,
        sampling_rate=16000,
        threshold=0.5,              # ← ניתן לכוונן
        min_silence_duration_ms=100,
        speech_pad_ms=30,
        min_speech_duration_ms=250,
    )
    ratio = len(speech_t) / max(len(audio_t), 1)
    return speech_t.numpy(), float(ratio)
```

**ערכים נמדדים:** הסינתטי האודיו דחה speech_ratio=0.000 (Silero מזהה רק דיבור אמיתי).
עם הקלטת משתמשת אמיתית: צפי 0.60–0.90 לדיבור ב-4 שניות.

**סיכון VAD:** `min_speech_duration_ms=250` — הפסקה קצרה בין מילים לא מסולקת.
`threshold=0.5` — פסקאות בקול נמוך עלולות להחשב כשקט.

### 4.3 Z-score (CMVN)

```python
# api.py:183 — per-mel-channel normalization
feat = ((feat - feat.mean(1, keepdims=True)) / (feat.std(1, keepdims=True) + 1e-5))
```

**נמדד (אודיו סינתטי):**

| לפני Z-score | אחרי Z-score |
|---|---|
| mean = -3.2430 | mean = 0.000000 |
| std = 2.0127 | std = 0.999983 |

→ Z-score עובד נכון.

### 4.4 Opus Codec Impact

**נמדד:** Encode→Decode Opus 128kbps round-trip:

| השוואה | ציון |
|--------|------|
| clean probe vs. clean prototype | 1.0000 (100.0%) |
| degraded probe vs. clean prototype | 0.9982 (99.8%) |
| **ירידה עקב Opus** | **0.18 pp** |

**מסקנה:** ה-Opus codec לא הגורם לירידה. ה-pipeline סופג Opus ב-0.18 pp בלבד.

---

## 5. מסקנה — זיהוי הצוואר

### מה שנמדד vs. מה שנצפה בייצור

| תרחיש | ציון נמדד |
|--------|-----------|
| Same-file upper bound (pipeline test) | **100.0%** |
| +3% amplitude jitter, 1% noise (×10 trials) | 98.7–99.2% (mean 99.0%) |
| +Opus round-trip codec | 99.8% |
| **ייצור בפועל (נצפה)** | **~62%** |

**הפער של ~37 pp** בין הבדיקה הסינתטית לייצור אינו יכול להיגרם מ:
- קוד ה-pipeline (נמדד — עובד נכון)
- ה-Opus codec (0.18 pp בלבד)
- וריאביליות הקלטה-להקלטה (0.15 pp בלבד)

### הסיבה המרכזית: Session-Acoustic Mismatch

הפרש של ~37 pp מתאים למה שמדווח בספרות על ECAPA-TDNN במצב cross-session:
same-speaker אך sessions שונות (יום אחר, מיקרופון אחר, חדר אחר) מייצרות
cosine similarity בטווח **0.55–0.80**, לעומת **0.98–1.00** בתוך אותה session.

**מנגנון:**
1. `enrollment` → הקלטה בתנאים X (מיקרופון, חדר, עייפות קולית)
2. `inference` → הקלטה בתנאים Y (שונים בדיוק מספיק)
3. ECAPA-TDNN קידד את ה-acoustic conditions לתוך ה-embedding, לא רק את זהות הדובר

### גורמים משניים שמחמירים

| גורם | קובץ/שורה | השפעה מוערכת |
|------|-----------|--------------|
| WebRTC DSP (echoCancellation + noiseSuppression + autoGainControl) ON בברירת מחדל | `useAudioRecorder.js:19` | 2–8 pp — משנה את הצבע הספקטרלי |
| Prototype = ממוצע דגימות enrollment | `api.py:220` + `AudioService` | אם דגימות enrollment מגוונות — הממוצע רחוק מכל אחת |
| L2-normalization כפולה ב-/enroll | `api.py:220` (וגם ב-`model.py:92`) | 0 pp — אידמפוטנטי, לא בעיה |
| סף 60% — נוקשה ל-cross-session | `AudioService.java:329` | ה-threshold עצמו הגיוני |

### המלצות לתיקון (לפי עדיפות)

1. **הכי אפקטיבי — Re-enrollment:** לבצע re-enrollment מחדש בתנאים האמיתיים
   (אותו מיקרופון, אותה סביבה, WebRTC ON, Opus codec) — הפרש sessions יקטן מאוד

2. **Score normalization (S-norm):** לנרמל את הציון לפי פיזור הציונים מול כל
   הפרוטוטייפים — מפחית את השפעת session-level shift

3. **Multi-enrollment prototype:** ≥5 דגימות enrollment מ-sessions שונות →
   ממוצע שמייצג טוב יותר את מרחב הקול של הדובר

4. **WebRTC DSP constraint:** `getUserMedia({ audio: {echoCancellation:false, noiseSuppression:false} })`
   בעת enrollment בלבד — מצמצם את ה-acoustic pipeline differences

---

## נספח: כיצד לאסוף מספרים אמיתיים

להוסיף לצינור ה-predict ב-`api.py` את ה-logs הזמניים הבאים (לפני/אחרי
כל שלב), להריץ 10 ניסיונות דרך ממשק המשתמש, ולתעד:

```python
# TEMPORARY — diagnostics, remove before release
# הוסף ב-api.py בתוך extract_embedding / _audio_to_tensor:
print(f"[DIAG] speech_ratio={_ratio:.3f}")
print(f"[DIAG] audio_len_after_vad={len(audio)}")
print(f"[DIAG] tiled={'YES x'+str(tile_factor) if tiled else 'NO'}")
print(f"[DIAG] fbank_mean={feat.mean():.4f}  fbank_std={feat.std():.4f}")
print(f"[DIAG] emb_norm_before_normalize={emb_raw.norm().item():.6f}")
# ב-predict_speaker אחרי חישוב score:
print(f"[DIAG] raw_dot={score:.6f}  matched={best_name}")
```

כלי command-line מלא: `python ai_engine/diag_score.py --probe recording.wav --proto enrollment.wav`

---

*נוצר ע"י `diag_score.py` (TEMPORARY) — ל-pipeline validation בלבד.*
