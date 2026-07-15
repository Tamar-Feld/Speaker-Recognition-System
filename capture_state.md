# capture_state.md — מצב לכידת האודיו

בדיקה בלבד. לא בוצעו שינויים.

---

## 1. אילוצי הלכידה (P1)

### ממצא

**קובץ:** `client/src/hooks/useAudioRecorder.js:19`

```js
const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
```

**הערך המדויק שמועבר:** `{ audio: true }` — הצורה הקצרה (shorthand boolean).

**משמעות:** הדפדפן בוחר את ברירות המחדל של WebRTC DSP לבדו. לפי מפרט WebRTC,
ברירת המחדל עבור `{ audio: true }` היא:
- `echoCancellation: true`
- `noiseSuppression: true`
- `autoGainControl: true`

כלל שלושת ה-DSP פילטרים **פעילים**.

**האם קיים קבוע משותף?** לא. אין `AUDIO_CONSTRAINTS` או קבוע דומה בשום מקום.
`getUserMedia` נקרא רק פעם אחת בפרויקט כולו — שורה 19 לעיל.

### מסקנה: P1 (DSP כבוי) — **לא קיים**

---

## 2. שיטת הלכידה — PCM או Opus (P3)

### ממצא

**קובץ:** `client/src/hooks/useAudioRecorder.js:20`

```js
const mr = new MediaRecorder(stream, { mimeType: 'audio/webm' })
```

**קובץ:** `client/src/hooks/useAudioRecorder.js:25`

```js
const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
```

**קובץ:** `client/src/services/api.js:26`

```js
fd.append('file', file, 'recording.webm')
```

שיטת הלכידה היא `MediaRecorder` עם container `audio/webm`. הקודק הממשי שנבחר
על-ידי Chrome/Edge עבור `audio/webm` בהיעדר ציון מפורש (`audio/webm;codecs=opus`)
הוא **Opus** (lossy, ~128 kbps).

**האם קיים AudioContext + AudioWorklet?**

- glob של `client/**/*.worklet*` — **לא נמצא שום קובץ**
- glob של `client/**/*pcm*` — **לא נמצא שום קובץ**
- אין `pcm-recorder.js` בפרויקט

**המסלול הפעיל:** `MediaRecorder` → `audio/webm` (Opus). אין מסלול PCM.

### מסקנה: P3 (PCM במקום Opus) — **לא קיים**

---

## 3. מסלול הרישום מול מסלול הזיהוי (P2)

### ממצא — מסלול הרישום (AdminPage)

**קובץ:** `client/src/pages/AdminPage.jsx:31`

```js
import useAudioRecorder from '../hooks/useAudioRecorder'
```

**קובץ:** `client/src/pages/AdminPage.jsx:63–68`

```js
const {
  audioBlob: enrollBlob,
  startRecording: startEnroll,
  stopRecording:  stopEnroll,
  error:          enrollMicError,
} = useAudioRecorder()
```

**קובץ:** `client/src/pages/AdminPage.jsx:82–97` — הקלטה של 5 שניות:

```js
const doEnrollRecord = async () => {
  setRegCountdown(5)
  setRegRecording(true)
  await startEnroll()
  // ...
  regStopTimer.current = setTimeout(() => {
    clearInterval(regCdInterval.current)
    stopEnroll()
    setRegRecording(false)
  }, 5000)
}
```

**קובץ:** `client/src/pages/AdminPage.jsx:163`

```js
regBlobs.forEach((blob, i) => fd.append('samples', blob, `sample_${i}.webm`))
```

### ממצא — מסלול הזיהוי (CorridorPage / ScanModal)

**קובץ:** `client/src/pages/CorridorPage.jsx:16`

```js
import useAudioRecorder from '../hooks/useAudioRecorder'
```

**קובץ:** `client/src/pages/CorridorPage.jsx:422`

```js
const { audioBlob, startRecording, stopRecording, error: micError } = useAudioRecorder()
```

**קובץ:** `client/src/pages/CorridorPage.jsx:443–446` — הקלטה של 5 שניות:

```js
stopTimer.current = setTimeout(() => {
  clearInterval(cdInterval.current)
  stopRecording()
}, 5000)
```

**קובץ:** `client/src/services/api.js:26`

```js
fd.append('file', file, 'recording.webm')
```

### האם DoorPage מקליטה?

**קובץ:** `client/src/pages/DoorPage.jsx:1–6` (תגובה בראש הקובץ):

```
// Reached only via navigate() from CorridorPage's ScanModal after a granted
// scan. Does NOT re-record or re-call the API — it purely animates the door
// opening using the result already carried in router state
```

`DoorPage` אינה מבצעת הקלטה כלל. כל ה-API calls הן דרך `ScanModal` ב-`CorridorPage`.

### מסקנה: P2 (רישום וזיהוי באותו מסלול) — **קיים**

---

## 4. עקביות בין הרישום לזיהוי

| מאפיין | מסלול רישום (`AdminPage`) | מסלול זיהוי (`ScanModal`) |
|--------|---------------------------|---------------------------|
| Hook | `useAudioRecorder` | `useAudioRecorder` |
| `getUserMedia` constraints | `{ audio: true }` (DSP ON) | `{ audio: true }` (DSP ON) |
| `MediaRecorder` mimeType | `'audio/webm'` (Opus) | `'audio/webm'` (Opus) |
| משך הקלטה | 5 שניות (timeout) | 5 שניות (timeout) |
| שם קובץ בשליחה | `sample_N.webm` | `recording.webm` |
| endpoint | `/api/users/register` | `/api/audio/upload` |

**המסלולים זהים לחלוטין** מבחינת קוד הלכידה. ההבדל היחיד: שם הקובץ ב-`FormData`
(`sample_0.webm` vs `recording.webm`) — לא משנה, FastAPI מתייחס לתוכן בלבד.

---

## 5. עדות ל-build הפועל

**glob של `client/dist/**/*`** — **לא נמצאו קבצים. תיקיית `dist/` אינה קיימת.**

המשמעות: ה-frontend **אינו מוגש כ-build סטטי**. השרת הפועל
(אם הופעל) הוא **Vite dev server** (`npm run dev`, פורט 3000).

האם קוד ה-source הנוכחי (זה שנקרא) הוא מה שרץ? אם Vite dev server מופעל
ישירות מ-`client/src/`, אז כן — dev server מגיש את הקבצים ישירות ב-HMR.
**לא ניתן לאשר ריצה בלי לבדוק אם השרת פועל**, אך לא קיים build ישן שיכול לסתור.

### מסקנה: ה-build הפועל מעודכן — **לא ידוע** (אין `dist/`; אם Vite רץ, הקוד עדכני)

---

## סיכום מסקנות

| תיקון | שאלה | מצב |
|--------|-------|------|
| **P1** | DSP כבוי (`echoCancellation:false` וכו') | **לא קיים** — `{ audio: true }` בלבד |
| **P3** | PCM במקום Opus | **לא קיים** — `MediaRecorder` + `audio/webm` (Opus) |
| **P2** | רישום וזיהוי באותו מסלול | **קיים** — אותו hook, אותה constraints, אותו codec |
| **build** | ה-build הפועל מעודכן | **לא ידוע** — אין `dist/`, Vite dev server בלבד |
