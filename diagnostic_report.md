# דוח אבחון: פער EER-Benchmark לעומת Production

## שלב 1 — מסלול ה-inference בפועל (קצה לקצה)

```
useAudioRecorder.js:19   getUserMedia({ audio: true })
                          ↓ MediaRecorder(stream, { mimeType: 'audio/webm' })
useAudioRecorder.js:23   ondataavailable → chunksRef
useAudioRecorder.js:25   Blob({ type: 'audio/webm' })
                          ↓
api.js:26                fd.append('file', blob, 'recording.webm')
api.js:30                POST /api/audio/upload
                          ↓
AudioController.java:110 audioService.identifyAndCheckAccess(file, roomId, clientIp)
AudioService.java:78     collectEnrolledEmbeddingsAsJson()  ← מ-MySQL
AudioService.java:168    callPredictApi(file, embeddingsJson)
                          ↓ MultipartFile bytes שוגרים ישירות ל-FastAPI (ללא שינוי)
api.py:256               convert_to_wav_mono_16khz(file)   ← FFmpeg: WebM → WAV 16kHz mono
api.py:259               extract_embedding(wav_path)
  api.py:157               sf.read(wav_path)               ← soundfile קורא WAV
  api.py:162               _apply_vad(audio)               ← Silero VAD threshold=0.5
  api.py:163               check SPEECH_RATIO_MIN=0.30
  api.py:166-168           RMS normalization → 0.1
  api.py:169-171           tile if < 48000 samples
  api.py:180               _spk_feat.extract(seg)          ← C++ Fbank [80, T]
  api.py:181               z-score per channel
  api.py:193               model(segs)                     ← ECAPA_TDNN forward
  api.py:194               embs.mean(0)                    ← mean over n_segs=5
api.py:260               F.normalize(emb_raw, p=2, dim=0)  ← L2-norm
api.py:284               dot(query, proto)                  ← cosine similarity
api.py:288               clip to [0, 1]                     ← threshold 0.60
AudioService.java:218    confidence < 0.60 → deny
```

### ודאות על המודל שנטען
- `api.py:34`: `from core.model import ECAPA_TDNN` — **core/model.py** (לא legacy Speaker1DCNN)
- `api.py:59`: `ECAPA_TDNN(C=512, emb=192, scale=8)` — תואם ל-C=512, scale=8, emb=192
- `core/model.py:76-78`: dilations=[2,3,4] — נכון
- `core/model.py:82`: fc_embed: 512*6=3072 → 192 — נכון
- `core/model.py:92`: `F.normalize(emb, p=2, dim=1)` — המודל מחזיר embedding מנורמל L2
- `api.py:65-67`: טעינת checkpoint עם `weights_only=True`, מטפל ב-nested dict — נכון

**המודל שנטען הוא ECAPA_TDNN הנכון.** לא קיים speaker_id_inference.py בעץ הקבצים הנוכחי — ה-legacy desync שהיה קיים בעבר כבר תוקן.

---

## שלב 2 — שלושת מבחני הבידוד (לא מבוצעים — מצב אבחון)

אלה המבחנים שיש לבצע וההשלכות הצפויות לכל תוצאה:

### מבחן A — עוקף דפדפן
```bash
curl -X POST http://127.0.0.1:8000/predict \
  -H "X-Api-Key: 5b61d4ae..." \
  -F "file=@some_enrolled_person.wav" \
  -F "embeddings=$(cat prototype.json)"
```
לקחת קובץ WAV גולמי שנקלט בתנאים דומים ל-enrollment ולהריץ ישירות.
- **אם confidence > 0.60** → הבעיה בלכידת הדפדפן (H1a, H1b, H1c). עבור לממצא P1-P3.
- **אם confidence < 0.60 גם עם WAV גולמי** → הבעיה ב-pipeline החילוץ (H2-H8).

### מבחן B — Self-similarity
לקחת קובץ WAV יחיד, לשלוח אותו גם ל-`/enroll` וגם ל-`/predict` עם ה-embedding שחזר:
```
cosine(extract(file), enroll(file)) ≈ 1.0 ?
```
- **אם < 0.95** → חילוץ הפיצ'רים / Z-score / L2-norm לא דטרמיניסטי — באג קריטי.
- **אם ≈ 1.0** → ה-pipeline יציב בפני עצמו. הבעיה בפער בין enrollment לפריצה.

### מבחן C — Dump אודיו בכל שלב (עיקרי ביותר)
להוסיף זמנית לוג לפני/אחרי כל שלב ב-`_audio_to_tensor()`:
```
שלב 0: מה FFmpeg מחזיר    — SR, ערוצים, משך, RMS, peak
שלב 1: אחרי VAD            — כמה שניות נשארו, ratio
שלב 2: אחרי RMS norm      — RMS == 0.1 ?
שלב 3: אחרי Fbank          — min/max per mel channel
שלב 4: אחרי z-score        — mean ≈ 0, std ≈ 1 per channel ?
```
ולהשוות מול קובץ VoxCeleb טיפוסי שהמודל אומן עליו. כל חריגה סטטיסטית מראה היכן ה-pipeline יוצא מהתפלגות האימון.

**Cohort test**: לשלוח הקלטות של 3 דוברים שונים מהמיקרופון. אם כולם נמוכים → באג pipeline שיטתי. אם חלקם גבוה → בעיית איכות לכידה/enrollment ספציפית.

---

## שלב 3 — טבלת השערות מדורגת לפי סבירות

---

### P1 — [CRITICAL / ביטחון גבוה] WebRTC DSP פעיל: echoCancellation + noiseSuppression + autoGainControl

**ראיה מהקוד:**
```javascript
// useAudioRecorder.js:19
const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
```
`{ audio: true }` הוא shorthand ל-`{ audio: {} }` — ברירות המחדל של Chrome/Firefox הן:
- `echoCancellation: true`
- `noiseSuppression: true`
- `autoGainControl: true`

**מה גורם לבעיה:**
שלושת הפילטרים הללו הם אלגוריתמי WebRTC DSP עם מטרה אחת: לשפר **מובנות דיבור** לאוזן האנושית. הם פועלים על ידי מחיקת בדיוק המאפיינים שמייחדים דובר ספציפי:
- **Noise Suppression**: מחסיר ספקטרום שנחשב "רעש" — אלו לרוב התדרים הגבוהים ואפיוני הגלוטיס שמזהים זהות
- **AGC (Automatic Gain Control)**: מנרמל עוצמה בין הקלטות — מבטל את השונות שה-RMS normalization שלנו צריך לנרמל בצורה קבועה
- **Echo Cancellation**: מזהה ומחסיר "הד" — עלול לפגוע בהרמוניקות של הקול

הפיצ'רים שנכנסים ל-Fbank ב-production שונים מהותית מאלה שהמודל ראה באימון (VoxCeleb לא עבר WebRTC processing).

**תיקון מוצע (לא מיושם):**
```javascript
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: false,
    noiseSuppression: false,
    autoGainControl: false,
    sampleRate: 16000    // מבקש 16kHz ישירות מהדפדפן
  }
})
```
**בדיקה מהירה:** להוסיף ב-`startRecording` לפני `mediaRecorderRef.current = mr`:
```javascript
console.log(stream.getAudioTracks()[0].getSettings())
```
זה מציג בדיוק אילו constraints פעילים. אם echoCancellation=true — P1 מאושר.

---

### P2 — [CRITICAL / ביטחון גבוה] Domain Mismatch: Enrollment מקובץ דיסק vs. Prediction מדפדפן

**ראיה מהקוד:**
```jsx
// AdminPage.jsx:103-104 — enrollment
regFiles.forEach((f) => fd.append('samples', f))
```
`regFiles` מגיע מ-file input (`<input type="file">`). המשתמש בוחר קבצים מהדיסק — לרוב WAV נקי, מלא, ללא WebRTC processing.

```javascript
// api.js:26-27 — prediction
fd.append('file', file, 'recording.webm')
```
הקובץ הוא `audioBlob` מ-MediaRecorder — WebM/Opus מהמיקרופון עם כל ה-DSP של הדפדפן.

**מה גורם לבעיה:**
- Enrollment embeddings: מחושבים על אודיו מהדיסק (clean, full-band, no WebRTC)
- Prediction embedding: מחושב על אודיו מהמיקרופון (WebRTC processed, Opus compressed)
- שני ה-embeddings ממופים לאזורים שונים ב-192D space
- הפרש cosine בין אותו דובר בשני הדומיינים עלול להיות 0.2-0.4 — מתחת לסף 0.60

**תיקון מוצע (לא מיושם):**
Enrollment צריך לעבור אותו pipeline בדיוק כמו prediction: הקלטה מהדפדפן עם אותם constraints (ולאחר תיקון P1 — ללא WebRTC DSP). כלומר, AdminPage צריך גם הוא להקליט דרך useAudioRecorder (או hook זהה) ולא דרך file input.

**בדיקה מהירה:** לקחת הקלטה מיקרופון גולמית, לשלוח ל-`/enroll`, לאחר מכן לשלוח הקלטה נוספת של אותו דובר ל-`/predict` עם ה-embedding שהוחזר. אם self-similarity (same-pipeline) גבוה יותר ממה שמקבלים ב-production — P2 מאושר.

---

### P3 — [HIGH / ביטחון גבוה] WebM/Opus Lossy Encoding

**ראיה מהקוד:**
```javascript
// useAudioRecorder.js:20
const mr = new MediaRecorder(stream, { mimeType: 'audio/webm' })
```
MediaRecorder עם audio/webm משתמש ב-Opus codec. Opus:
- בקצב ברירת מחדל (לרוב 32-128 kbps בדפדפן) מיישם lossy perceptual coding
- מוסיר תוכן ספקטרלי שאינו "שמיע" לאדם — אבל עשוי להיות קריטי לזיהוי ביומטרי
- pre-emphasis ב-`speaker_features.cpp:108-113` מגביר בדיוק את התדרים הגבוהים הרגישים לאיכות

לאחר FFmpeg decode ב-`api.py:103-118`, ה-PCM המשוחזר כבר אינו זהה ל-PCM המקורי.

**תיקון מוצע (לא מיושם):**
```javascript
// לבדוק תמיכה בדפדפן לפני שימוש:
const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=pcm') ? 'audio/webm;codecs=pcm'
               : 'audio/webm'  // fallback
const mr = new MediaRecorder(stream, { mimeType })
```
Chrome לא תומך ב-WAV ישירות. `audio/webm;codecs=pcm` (PCM ב-container WebM) — תמיכה חלקית. הפתרון הנכון לטווח ארוך הוא Web Audio API לגנוז PCM ישירות.

**בדיקה מהירה:** להשוות spectrograms של (1) קובץ WAV מקורי ו-(2) אותו קובץ לאחר encode/decode Opus. ההפרש ב-high-mel-bands מראה את ה-damage.

---

### P4 — [HIGH / ביטחון בינוני] S-Norm לא מיושם: הסף 0.60 לא מכויל

**ראיה מהקוד:**
```python
# api.py:284-288
score = torch.dot(query, proto).item()
confidence = float(min(max(best_score, 0.0), 1.0))
```
```java
// AudioService.java:218
if (confidence < confidenceThreshold)  // 0.60
```

**מה גורם לבעיה:**
EER ~3.49% הושג עם S-Norm. S-Norm ממיר cosine scores גולמיים ל-normalized scores לפי פיזור של cohort:
```
s_norm = (s - μ_impostor) / σ_impostor
```
ה-normalized scores נמדדים בסקאלה שונה לחלוטין מה-raw cosine (0-1). אם הסף 0.60 נקבע על סקאלת S-Norm — הוא לא תקף ל-raw cosine.

גם בלי S-Norm: הסף 0.60 על raw cosine similarity בין embeddings ECAPA-TDNN צריך להיות מכויל ספציפית על ה-pipeline שבו נעשה שימוש (אותו מיקרופון, אותה preprocessing). ייתכן שהסף הנכון ל-production הוא 0.40 או 0.45.

**תיקון מוצע (לא מיושם):**
לאסוף 50-100 זוגות same-speaker ו-50-100 זוגות different-speaker מהמיקרופון הספציפי, לחשב DET curve, ולקבוע את הסף בנקודת EER. מבלי S-Norm, הסף צריך לשקף את פיזור ה-raw cosine scores בפועל.

**בדיקה מהירה:** להדפיס raw cosine scores של 20 ניסיונות זיהוי ללוגים. אם scores של הדובר הנכון ≈ 0.45-0.58 — הסף 0.60 גורם ל-deny לגיטימי.

---

### P5 — [MEDIUM / ביטחון בינוני] Fbank C++ לעומת Fbank אימון — לא ניתן לאמת

**ראיה מהקוד — `speaker_features.cpp`:**
```cpp
// Lines 21-32
N_FFT = 512, WIN_LENGTH = 400 (25ms), HOP_LENGTH = 160 (10ms)
N_MELS = 80, PREEMPH_COEF = 0.97
F_MIN = 20.0, F_MAX = 7600.0
// Line 43-47: hz_to_mel = 2595 * log10(1 + hz/700) — HTK-style
// Line 163: std::log(dot + LOG_FLOOR) — natural log
// Lines 58-60: Hamming window: 0.54 - 0.46*cos(2π*n/N)
```

**נקודות שלא ניתן לאמת (קוד האימון `ecapa_final_v2.py` לא זמין):**
- **HTK vs. Slaney Mel Normalization**: `librosa.filters.mel` ברירת המחדל היא `norm='slaney'` — מנרמלת את גובה המשולש. C++ **לא** מנרמל (HTK-style). זה יוצר filterbank shapes שונים, במיוחד בתדרים הנמוכים.
- **log vs. log10**: C++ משתמש `std::log` (natural log). אם האימון השתמש `10*log10` (power dB) — ההבדל הוא קבוע כפלי ×10/ln(10)≈4.34, שה-z-score מבטל, אז ה-impact קטן אבל קיים.
- **CMVN vs. z-score**: C++ עושה z-score per channel (global mean+std per segment). CMVN מוחל per-utterance. אם האימון השתמש ב-CMVN על utterances ארוכות — הנרמול שונה.
- **חלון Hamming**: C++ משתמש `0.54 - 0.46*cos` — זה ה-Hamming הסטנדרטי. ספריות Python לרוב משתמשות בדיוק זה. סביר להניח שזה תואם.

**תיקון/אימות מוצע (לא מיושם):**
לקחת 1 שנייה WAV 16kHz, לחשב Fbank עם C++ ועם librosa/torchaudio בפרמטרים שונים, ולהשוות pixel-by-pixel.

---

### P6 — [LOW / ביטחון נמוך] VAD threshold עלול לדחות הקלטות לגיטימיות

**ראיה מהקוד:**
```python
# api.py:46
SPEECH_RATIO_MIN = 0.30  # "0.30 is a starting placeholder — calibrate empirically"
```
```python
# api.py:146-148
timestamps = _vad_timestamps(
    audio_t, _vad_model, sampling_rate=_SR,
    threshold=0.5,  ← Silero confidence threshold
    ...
)
```

**אנליזה:**
- VAD מחובר ועובד (ממצא חיובי)
- אחרי WebRTC Noise Suppression (P1), הקול שמגיע ל-VAD עלול להיראות "שקט" יותר → Silero לא מסמן כ-speech → ratio < 0.30 → דחיית ה-request עם HTTP 422
- הלוג של Spring Boot יראה `"Insufficient speech detected (VAD)"` בשדה `rejectionReason`
- זה יסביר probes שנדחים לחלוטין (confidence=0) ולא רק probes עם confidence נמוכה

**בדיקה מהירה:** לבדוק ב-MySQL את שדה `rejection_reason` ב-`access_logs`. אם יש שורות עם "Insufficient speech (VAD)" — P6 פעיל.

---

### P7 — [LOW / ביטחון גבוה] L2-norm ו-Cosine — נכון, אין בעיה

**ראיה מהקוד:**
```python
# api.py:210 (enroll): emb = F.normalize(emb_raw, p=2, dim=0)
# api.py:250 (predict candidates): candidates[username] = F.normalize(vec, p=2, dim=0)
# api.py:260 (predict query): query = F.normalize(emb_raw, p=2, dim=0)
# api.py:284: score = torch.dot(query, proto).item()
```
```java
// UserService.java:183-197: averageAndNormalize() — ממוצע + L2-norm
```
הממוצע של L2-normalized vectors עם חזרה ל-norm — תקין לחישוב centroid על unit sphere.

---

## שלב 4 — הפרדה: שיטתי vs. סלקטיבי

### סיבות שיטתיות ל-pipeline (כל הדוברים מושפעים):

| # | השערה | File:Line | ביטחון |
|---|--------|-----------|--------|
| P1 | WebRTC DSP פעיל (EC+NS+AGC) | `useAudioRecorder.js:19` | **גבוה מאוד** |
| P3 | Opus lossy encoding | `useAudioRecorder.js:20` | **גבוה** |
| P4 | S-Norm לא מיושם / סף לא מכויל | `AudioService.java:218` | **בינוני** |
| P5 | Fbank C++ vs. training mismatch | `speaker_features.cpp:43,163` | **בינוני** |

### בעיות לכידה/enrollment ספציפיות:

| # | השערה | File:Line | ביטחון |
|---|--------|-----------|--------|
| P2 | Enrollment מדיסק, Predict מדפדפן | `AdminPage.jsx:103` vs `api.js:26` | **גבוה מאוד** |
| P6 | VAD threshold דוחה הקלטות לגיטימיות | `api.py:46`, `api.py:146` | **נמוך-בינוני** |

---

## סיכום והמלצה לסדר תיקונים

**הסיבה הסבירה ביותר לתסמין הספציפי** (EER טוב, production גרוע): **P1 + P2 בשילוב** — WebRTC DSP משנה את האודיו הנכנס ביחס לאימון (P1), ו-enrollment נעשה מדיסק בלי WebRTC בעוד prediction מהמיקרופון עם WebRTC (P2). זה מסביר domain shift כפול ושיטתי.

**סדר תיקונים מוצע (לאישורך בנפרד):**
1. **P1** — הוסף `echoCancellation:false, noiseSuppression:false, autoGainControl:false` ל-getUserMedia
2. **P2** — שנה enrollment ב-AdminPage לעבור דרך microphone recording (אותו hook)
3. **P4** — כייל מחדש את הסף 0.60 אחרי תיקון P1+P2
4. **P3** — שקול Web Audio API ל-PCM גולמי במקום MediaRecorder/Opus
5. **P5** — אמת Fbank parameters מול training code

**מבחן A** (WAV גולמי ישירות ל-FastAPI) יאשר אם P1+P2+P3 הם האחראים הראשיים. אם cosine > 0.60 עם WAV גולמי — כל הבעיה בשכבת הדפדפן.
