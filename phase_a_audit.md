# Phase A — דוח אבחון (Read-Only) · `ai_engine/api.py`

---

## A.1 — כל המחלקות המודל-קשורות עם טווחי שורות מדויקים

כולן מקובצות תחת הכותרת `# ECAPA-TDNN Layer Definitions` (שורה 64–66):

| מחלקה | שורות | תפקיד |
|-------|--------|--------|
| `SEBlock1d` | 67–76 | Squeeze-and-Excitation attention על ציר הערוצים |
| `Res2Conv1dReluBn` | 78–95 | Multi-scale 1D conv עם dilation וחלוקה לרוחב |
| `SERes2Block` | 97–113 | בלוק residual שלם: SE + Res2 + BN |
| `AttentivePool` | 115–127 | Attentive statistics pooling (mean+std משוקלל) |
| `ECAPA_TDNN` | 129–151 | הארכיטקטורה הראשית — stem + 3 בלוקים + MFA + pooling + embedding |

גבול ברור: הבלוק המודל מסתיים בשורה 151 (`return F.normalize(emb, p=2, dim=1)`).
השורה הבאה (154): `# ── App & model state ─────` — כבר לא מודל.

---

## A.2 — חתימת הבנאי המדויקת של `ECAPA_TDNN`

```python
# api.py:129–130
class ECAPA_TDNN(nn.Module):
    def __init__(self, C=512, emb=192, scale=8):
```

שלושה פרמטרים, כולם עם ברירות מחדל. `scale` מועבר ישירות ל-`SERes2Block` בשורות 135–137 (`self.block1 = SERes2Block(C, 2, scale)`).

---

## A.3 — כל אתרי האינסטנציה עם הפרמטרים בפועל

**אתר יחיד:**

```python
# api.py:168 — בתוך load_model_to_ram()
model = ECAPA_TDNN(C=512, emb=192, scale=8)
```

גם שורה 159 מכילה annotation:
```python
# api.py:159
model: ECAPA_TDNN = None
```
זהו type hint בלבד, לא אינסטנציה.

---

## A.4 — שורות טעינת המשקלים

```python
# api.py:175
ckpt  = torch.load(model_path, map_location=device, weights_only=True)
# api.py:176
state = ckpt.get("model", ckpt.get("state_dict", ckpt)) if isinstance(ckpt, dict) else ckpt
# api.py:177
model.load_state_dict(state)
```

- **`strict` mode:** ברירת מחדל (`True`) — אין מעבר של `strict=False`
- **מבנה ה-checkpoint:** שלוש אפשרויות מטופלות:
  - dict עם מפתח `"model"` → `ckpt["model"]`
  - dict עם מפתח `"state_dict"` → `ckpt["state_dict"]`
  - כל דבר אחר (flat dict / raw) → `ckpt` כמו שהוא
- **`model_path`:** `os.path.join(_AI_ENGINE_DIR, "core", "weights", "best_model.pt")` — שורה 169

---

## A.5 — אימות grep: כמה `class ECAPA_TDNN` קיימות בפרויקט?

```
ai_engine/api.py:129:class ECAPA_TDNN(nn.Module):
```

**תוצאה: הגדרה אחת בלבד.** הקבצים שכללו הגדרות כפולות (`core/model.py`, `speaker_id_inference.py`) נמחקו בשלב Group 1.

---

## A.6 — קבועים גלובליים שהמודל תלוי בהם

**הקלאסים עצמם (שורות 67–151) לא תלויים באף קבוע גלובלי** — הם מכילים רק `torch`, `nn`, `F`. הם מחלקות PyTorch טהורות.

הקבועים הבאים שייכים ל**preprocessing**, לא למודל:

| קבוע | שורה | מה משתמש בו |
|------|------|-------------|
| `_SR = 16_000` | 50 | `_audio_to_tensor`, `_apply_vad` |
| `_TARGET_SAMPLES = int(3.0 * _SR)` | 51 | `_audio_to_tensor` |
| `_N_FFT = 512` | 52 | `_FIXED_T` בלבד |
| `_HOP = 160` | 53 | `_FIXED_T` בלבד |
| `_FIXED_T = 297` | 54 | `_audio_to_tensor:314–315` |
| `_RMS_TARGET = 0.1` | 55 | `_audio_to_tensor:296` |
| `SPEECH_RATIO_MIN = 0.30` | 60 | `_audio_to_tensor:290` |
| `device = torch.device(...)` | 158 | `load_model_to_ram():183`, `extract_embedding():322` |

**למטרות השלב B:** רק `device` רלוונטי — הוא משמש ב-`load_model_to_ram()` ו-`extract_embedding()`, ושניהם **נשארים ב-`api.py`**. מקבץ הקלאסים (67–151) עצמאי לחלוטין מכל קבוע.

---

## A.7 — קוד שאסור לגעת בו

| קטע | שורות | הסבר |
|-----|--------|-------|
| כל ה-imports | 14–46 | כולל path setup ו-C++ module load |
| Audio & VAD constants | 49–61 | `_SR`, `_FIXED_T`, `SPEECH_RATIO_MIN`, `_vad_model = None` |
| `torch.set_num_threads(1)` | 155 | ביצועים ב-CPU |
| `app = FastAPI(...)` | 157 | נקודת כניסה FastAPI |
| `device = torch.device(...)` | 158 | משמש גם ב-startup וגם ב-`extract_embedding` |
| `model: ECAPA_TDNN = None` | 159 | משתנה גלובלי שה-startup ממלא |
| `weights_loaded = False` | 160 | דגל guard בשני ה-endpoints |
| `load_model_to_ram()` | 164–199 | startup event: יוצר מודל, טוען משקלים, טוען VAD |
| `convert_to_wav_mono_16khz()` | 202–251 | FFmpeg converter |
| `_apply_vad()` | 254–276 | Silero VAD wrapper |
| `_audio_to_tensor()` | 279–318 | Feature extraction pipeline |
| `extract_embedding()` | 320–325 | מריץ את המודל |
| `enroll_speaker()` | 328–365 | `POST /enroll` endpoint |
| `predict_speaker()` | 368–434 | `POST /predict` endpoint |
| `if __name__ == "__main__":` | 437–438 | uvicorn entry point |

---

## סיכום Phase A

**מה שייצא לקובץ חדש (בלבד):** שורות 64–151 — בלוק הכותרת + 5 הקלאסים.

**מיקום מוצע:** `ai_engine/core/model.py` — הקובץ הזה נמחק ב-Group 1 (היה גרסה שגויה), ולכן כתיבתו מחדש לא תגרום conflict. Import מ-`api.py` יהיה:
```python
from core.model import SEBlock1d, Res2Conv1dReluBn, SERes2Block, AttentivePool, ECAPA_TDNN
```
(עובד כי `api.py` רץ מ-`ai_engine/` שמוסף ל-`sys.path` אוטומטית).

---

> **סטטוס:** שלב A הושלם. ממתין לאישור לשלב B.
