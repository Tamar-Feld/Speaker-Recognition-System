# SpeakKey — דוח חילוץ נתונים לפרק 21
נוצר אוטומטית ב-2026-06-22 07:29 | תיקיית סריקה: `C:\Users\WIN 11\Documents\SpeakerAuth`

## ✅ קבצים שנמצאו (4):
- `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\api.py`
- `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\speaker_id_inference.py`
- `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\core\model.py`
- `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\core\pipeline.py`


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\api.py`

**Docstring של הקובץ:**
```
ai_engine/api.py — SpeakerAuth FastAPI Server
==============================================
Stateless architecture:
  /enroll  — One audio file → 192-dim L2-normalized embedding returned to Java.
             Java persists it to MySQL. Python holds no state after the response.
  /predict — Audio + all enrolled embeddings sent by Java → best-match speaker
             + confidence (0.0–1.0). Python caches nothing between requests.

Only the ECAPA-TDNN model weights are held in RAM between requests.
Python never opens a database connection. MySQL is exclusively owned by Spring Boot.
```

**קבועים גלובליים (Hyperparameters):**
```python
_AI_ENGINE_DIR = os.path.dirname(os.path.abspath(__file__))
_SR = 16000
_TARGET_SAMPLES = int(3.0 * _SR)
_N_FFT = 512
_HOP = 160
_FIXED_T = (_TARGET_SAMPLES - _N_FFT) // _HOP + 1
```


### 🏛️ מחלקה: `SEBlock1d`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.pool` = `nn.AdaptiveAvgPool1d(1)`
- `self.fc` = `nn.Sequential(nn.Linear(ch, ch // r, bias=False), nn.ReLU(inplace=True), nn.Linear(ch // r…`

**רשימת מתודות:** `__init__(self, ch, r=4)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `SEBlock1d.__init__`
```python
def __init__(self, ch, r=4):
        super().__init__()
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.fc   = nn.Sequential(
            nn.Linear(ch, ch // r, bias=False), nn.ReLU(inplace=True),
            nn.Linear(ch // r, ch, bias=False), nn.Sigmoid())
```

#### 🔑 מתודה קריטית: `SEBlock1d.forward`
```python
def forward(self, x):
        b, c, _ = x.shape
        return x * self.fc(self.pool(x).view(b, c)).view(b, c, 1)
```

### 🏛️ מחלקה: `Res2Conv1dReluBn`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.width` = `ch // scale`
- `self.nums` = `scale - 1`
- `self.convs` = `nn.ModuleList([nn.Conv1d(self.width, self.width, 3, dilation=dil, padding=dil, bias=False)…`
- `self.bns` = `nn.ModuleList([nn.BatchNorm1d(self.width) for _ in range(self.nums)])`

**רשימת מתודות:** `__init__(self, ch, scale=8, dil=1)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `Res2Conv1dReluBn.__init__`
```python
def __init__(self, ch, scale=8, dil=1):
        super().__init__()
        self.width = ch // scale
        self.nums  = scale - 1
        self.convs = nn.ModuleList([
            nn.Conv1d(self.width, self.width, 3, dilation=dil, padding=dil, bias=False)
            for _ in range(self.nums)])
        self.bns = nn.ModuleList([nn.BatchNorm1d(self.width) for _ in range(self.nums)])
```

#### 🔑 מתודה קריטית: `Res2Conv1dReluBn.forward`
```python
def forward(self, x):
        spx = torch.split(x, self.width, 1)
        out, sp = [], None
        for i, (c, b) in enumerate(zip(self.convs, self.bns)):
            sp = spx[i] if i == 0 else sp + spx[i]
            sp = F.relu(b(c(sp)))
            out.append(sp)
        out.append(spx[self.nums])
        return torch.cat(out, 1)
```

### 🏛️ מחלקה: `SERes2Block`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.conv1` = `nn.Conv1d(ch, ch, 1, bias=False)`
- `self.bn1` = `nn.BatchNorm1d(ch)`
- `self.res2` = `Res2Conv1dReluBn(ch, scale, dil)`
- `self.conv3` = `nn.Conv1d(ch, ch, 1, bias=False)`
- `self.bn3` = `nn.BatchNorm1d(ch)`
- `self.se` = `SEBlock1d(ch)`
- `self.relu` = `nn.ReLU(inplace=True)`

**רשימת מתודות:** `__init__(self, ch, dil, scale=8)`, `forward(self, x, residual=None)`

#### 🔑 מתודה קריטית: `SERes2Block.__init__`
```python
def __init__(self, ch, dil, scale=8):
        super().__init__()
        self.conv1 = nn.Conv1d(ch, ch, 1, bias=False)
        self.bn1   = nn.BatchNorm1d(ch)
        self.res2  = Res2Conv1dReluBn(ch, scale, dil)
        self.conv3 = nn.Conv1d(ch, ch, 1, bias=False)
        self.bn3   = nn.BatchNorm1d(ch)
        self.se    = SEBlock1d(ch)
        self.relu  = nn.ReLU(inplace=True)
```

#### 🔑 מתודה קריטית: `SERes2Block.forward`
```python
def forward(self, x, residual=None):
        if residual is None: residual = x
        o = self.relu(self.bn1(self.conv1(x)))
        o = self.res2(o)
        o = self.relu(self.bn3(self.conv3(o)))
        o = self.se(o)
        return o + residual
```

### 🏛️ מחלקה: `AttentivePool`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.tdnn` = `nn.Conv1d(C * 3, 128, 1)`
- `self.attn` = `nn.Conv1d(128, C, 1)`

**רשימת מתודות:** `__init__(self, C)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `AttentivePool.__init__`
```python
def __init__(self, C):
        super().__init__()
        self.tdnn = nn.Conv1d(C * 3, 128, 1)
        self.attn = nn.Conv1d(128, C, 1)
```

#### 🔑 מתודה קריטית: `AttentivePool.forward`
```python
def forward(self, x):
        mu    = x.mean(2, keepdim=True).expand_as(x)
        sg    = x.var(2,  keepdim=True).clamp(1e-4).sqrt().expand_as(x)
        ctx   = torch.cat([x, mu, sg], 1)
        alpha = F.softmax(self.attn(torch.tanh(self.tdnn(ctx))), dim=2)
        mean  = (alpha * x).sum(2)
        std   = ((alpha * x.pow(2)).sum(2) - mean.pow(2) + 1e-4).sqrt()
        return torch.cat([mean, std], 1)
```

### 🏛️ מחלקה: `ECAPA_TDNN`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.stem` = `nn.Sequential(nn.Conv1d(80, C, 5, padding=2, bias=False), nn.BatchNorm1d(C), nn.ReLU(inpla…`
- `self.block1` = `SERes2Block(C, 2, scale)`
- `self.block2` = `SERes2Block(C, 3, scale)`
- `self.block3` = `SERes2Block(C, 4, scale)`
- `self.mfa_conv` = `nn.Conv1d(C * 3, C * 3, 1)`
- `self.pooling` = `AttentivePool(C * 3)`
- `self.bn_pool` = `nn.BatchNorm1d(C * 6)`
- `self.fc_embed` = `nn.Linear(C * 6, emb)`
- `self.bn_embed` = `nn.BatchNorm1d(emb)`

**רשימת מתודות:** `__init__(self, C=512, emb=192, scale=8)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `ECAPA_TDNN.__init__`
```python
def __init__(self, C=512, emb=192, scale=8):
        super().__init__()
        self.stem     = nn.Sequential(
            nn.Conv1d(80, C, 5, padding=2, bias=False),
            nn.BatchNorm1d(C), nn.ReLU(inplace=True))
        self.block1   = SERes2Block(C, 2, scale)
        self.block2   = SERes2Block(C, 3, scale)
        self.block3   = SERes2Block(C, 4, scale)
        self.mfa_conv = nn.Conv1d(C * 3, C * 3, 1)
        self.pooling  = AttentivePool(C * 3)
        self.bn_pool  = nn.BatchNorm1d(C * 6)
        self.fc_embed = nn.Linear(C * 6, emb)
        self.bn_embed = nn.BatchNorm1d(emb)
```

#### 🔑 מתודה קריטית: `ECAPA_TDNN.forward`
```python
def forward(self, x):
        h  = self.stem(x)
        o1 = self.block1(h,  residual=h)
        o2 = self.block2(o1, residual=h + o1)
        o3 = self.block3(o2, residual=h + o1 + o2)
        mfa   = self.mfa_conv(torch.cat([o1, o2, o3], 1))
        stats = self.pooling(mfa)
        emb   = self.bn_embed(self.fc_embed(self.bn_pool(stats)))
        return F.normalize(emb, p=2, dim=1)
```

### 🔧 פונקציה עצמאית: `enroll_speaker(file: UploadFile=File(...))`
```python
def enroll_speaker(file: UploadFile = File(...)):
    if not weights_loaded:
        raise HTTPException(status_code=500, detail="AI model weights not loaded.")

    print(f"\n🆕 [AI Server] /enroll ← {file.filename}")

    # 1. Convert the uploaded file to a physical WAV file
    wav_path = convert_to_wav_mono_16khz(file)

    try:
        # 2. Extract embedding using the physical WAV file path
        emb_raw = extract_embedding(wav_path)
        emb     = F.normalize(emb_raw, p=2, dim=0)
    except Exception as e:
        print(f"❌ [AI Server] Feature extraction failed: {e}")
        print(traceback.format_exc())
        raise HTTPException(status_code=400, detail=f"Audio processing failed: {str(e)}")
    finally:
        # 3. Clean up the physical file
        if wav_path and os.path.exists(wav_path):
            try: os.remove(wav_path)
            except OSError: pass

    print("✅ [AI Server] Embedding computed. Returning to Java.")
    return {"embedding": emb.tolist()}
```

### 🔧 פונקציה עצמאית: `predict_speaker(file: UploadFile=File(...), embeddings: str=Form(...))`
```python
def predict_speaker(
        file:       UploadFile = File(...),
        embeddings: str        = Form(...)):

    if not weights_loaded:
        raise HTTPException(status_code=500, detail="AI model weights not loaded.")

    print(f"\n📥 [AI Server] /predict ← {file.filename}")

    try:
        candidates_raw = json.loads(embeddings)
    except Exception as e:
        print(f"⚠️  [AI Server] Invalid embeddings JSON: {e}")
        return {"matched_username": None, "confidence": 0.0}

    candidates = {}
    for username, emb_list in candidates_raw.items():
        try:
            vec = torch.tensor(emb_list, dtype=torch.float32)
            candidates[username] = F.normalize(vec, p=2, dim=0)
        except Exception:
            pass

    if not candidates:
        return {"matched_username": None, "confidence": 0.0}

    # 1. Convert the uploaded file to a physical WAV file safely
    wav_path = convert_to_wav_mono_16khz(file)

    try:
        # 2. Extract embedding using the physical WAV file path
        emb_raw = extract_embedding(wav_path)
        query   = F.normalize(emb_raw, p=2, dim=0)
    except Exception as e:
        print(f"❌ [AI Server] Feature extraction failed: {e}")
        print(traceback.format_exc())
        raise HTTPException(status_code=400, detail=f"Audio processing failed: {str(e)}")
    finally:
        # 3. Clean up the physical file
        if wav_path and os.path.exists(wav_path):
            try: os.remove(wav_path)
            except OSError: pass

    best_name  = None
    best_score = -1.0
    for username, proto in candidates.items():
        score = torch.dot(query, proto).item()
        if score > best_score:
            best_score = score
            best_name  = username

    confidence = float(min(max(best_score, 0.0), 1.0))
    print(f"🎯 [AI Server] Best match: {best_name}  confidence={confidence:.4f}")
    return {"matched_username": best_name, "confidence": confidence}
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`api.py` (28 נמצאו):
```
L    5 [embedding]  /enroll  — One audio file → 192-dim L2-normalized embedding returned to Java.
L    7 [embedding]  /predict — Audio + all enrolled embeddings sent by Java → best-match speaker
L   24 [soundfile]  import soundfile as _sf
L   29 [UploadFile]  from fastapi import FastAPI, File, UploadFile, HTTPException, Form
L   48 [16_000]  _SR             = 16_000
L  160 [best_model]  model_path = os.path.join(_AI_ENGINE_DIR, "core", "weights", "best_model.pt")
L  166 [torch.load]  ckpt  = torch.load(model_path, map_location=device, weights_only=True)
L  167 [state_dict]  state = ckpt.get("model", ckpt.get("state_dict", ckpt)) if isinstance(ckpt, dict) else ckpt
L  168 [state_dict]  model.load_state_dict(state)
L  176 [embedding]  print("   Stateless mode: Java sends all enrolled embeddings on every /predict request.")
L  180 [UploadFile]  def convert_to_wav_mono_16khz(incoming_file: UploadFile) -> str:
L  186 [.wav]  temp_output = os.path.join(temp_dir, f"converted_audio_{uuid.uuid4().hex}.wav")
L  189 [UploadFile]  # Write the raw bytes from FastAPI UploadFile to disk
L  202 [16000]  "-ar", "16000",        # Force 16000Hz sample rate
L  265 [embedding]  def extract_embedding(audio_src, n_segs: int = 5) -> torch.Tensor:
L  274 [@app.post]  @app.post("/enroll")
L  275 [UploadFile]  def enroll_speaker(file: UploadFile = File(...)):
L  285 [embedding]  # 2. Extract embedding using the physical WAV file path
L  286 [embedding]  emb_raw = extract_embedding(wav_path)
L  298 [embedding]  print("✅ [AI Server] Embedding computed. Returning to Java.")
L  299 [embedding]  return {"embedding": emb.tolist()}
L  303 [@app.post]  @app.post("/predict")
L  305 [UploadFile]  file:       UploadFile = File(...),
L  306 [embedding]  embeddings: str        = Form(...)):
L  314 [embedding]  candidates_raw = json.loads(embeddings)
L  316 [embedding]  print(f"⚠️  [AI Server] Invalid embeddings JSON: {e}")
L  334 [embedding]  # 2. Extract embedding using the physical WAV file path
L  335 [embedding]  emb_raw = extract_embedding(wav_path)
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\speaker_id_inference.py`

**Docstring של הקובץ:**
```
speaker_id_inference.py — 12-Speaker Identification on i5 CPU
══════════════════════════════════════════════════════════════
Usage:
  python speaker_id_inference.py enroll  --speaker alice  --wavs audio/alice/*.wav
  python speaker_id_inference.py enroll  --speaker bob    --wavs audio/bob/*.wav
  python speaker_id_inference.py identify --wav  audio/unknown.wav
  python speaker_id_inference.py identify --wav  audio/unknown.wav --top 3
  python speaker_id_inference.py list
  python speaker_id_inference.py remove --speaker alice
```

**קבועים גלובליים (Hyperparameters):**
```python
MODEL_PATH = 'core\\weights\\best_model.pt'
PROFILE_FILE = '..\\shared_data\\embeddings_backup\\speaker_profiles.json'
ENGINE_PATH = 'dsp_module\\speaker_features.pyd'
_SR = 16000
_N_FFT = 512
_HOP = 160
_TARGET_SAMPLES = int(3.0 * _SR)
_FIXED_T = (_TARGET_SAMPLES - _N_FFT) // _HOP + 1
```


### 🏛️ מחלקה: `SEBlock1d`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.pool` = `nn.AdaptiveAvgPool1d(1)`
- `self.fc` = `nn.Sequential(nn.Linear(ch, ch // r, bias=False), nn.ReLU(inplace=True), nn.Linear(ch // r…`

**רשימת מתודות:** `__init__(self, ch, r=4)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `SEBlock1d.__init__`
```python
def __init__(self, ch, r=4):
        super().__init__()
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.fc   = nn.Sequential(
            nn.Linear(ch, ch//r, bias=False), nn.ReLU(inplace=True),
            nn.Linear(ch//r, ch, bias=False), nn.Sigmoid())
```

#### 🔑 מתודה קריטית: `SEBlock1d.forward`
```python
def forward(self, x):
        b, c, _ = x.shape
        return x * self.fc(self.pool(x).view(b, c)).view(b, c, 1)
```

### 🏛️ מחלקה: `Res2Conv1dReluBn`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.width` = `ch // scale`
- `self.nums` = `scale - 1`
- `self.convs` = `nn.ModuleList([nn.Conv1d(self.width, self.width, 3, dilation=dil, padding=dil, bias=False)…`
- `self.bns` = `nn.ModuleList([nn.BatchNorm1d(self.width) for _ in range(self.nums)])`

**רשימת מתודות:** `__init__(self, ch, scale=8, dil=1)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `Res2Conv1dReluBn.__init__`
```python
def __init__(self, ch, scale=8, dil=1):
        super().__init__()
        self.width = ch // scale
        self.nums  = scale - 1
        self.convs = nn.ModuleList([
            nn.Conv1d(self.width, self.width, 3,
                      dilation=dil, padding=dil, bias=False)
            for _ in range(self.nums)])
        self.bns = nn.ModuleList([nn.BatchNorm1d(self.width)
                                  for _ in range(self.nums)])
```

#### 🔑 מתודה קריטית: `Res2Conv1dReluBn.forward`
```python
def forward(self, x):
        spx = torch.split(x, self.width, 1)
        out, sp = [], None
        for i, (c, b) in enumerate(zip(self.convs, self.bns)):
            sp = spx[i] if i == 0 else sp + spx[i]
            sp = F.relu(b(c(sp)))
            out.append(sp)
        out.append(spx[self.nums])
        return torch.cat(out, 1)
```

### 🏛️ מחלקה: `SERes2Block`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.conv1` = `nn.Conv1d(ch, ch, 1, bias=False)`
- `self.bn1` = `nn.BatchNorm1d(ch)`
- `self.res2` = `Res2Conv1dReluBn(ch, scale, dil)`
- `self.conv3` = `nn.Conv1d(ch, ch, 1, bias=False)`
- `self.bn3` = `nn.BatchNorm1d(ch)`
- `self.se` = `SEBlock1d(ch)`
- `self.relu` = `nn.ReLU(inplace=True)`

**רשימת מתודות:** `__init__(self, ch, dil, scale=8)`, `forward(self, x, residual=None)`

#### 🔑 מתודה קריטית: `SERes2Block.__init__`
```python
def __init__(self, ch, dil, scale=8):
        super().__init__()
        self.conv1 = nn.Conv1d(ch, ch, 1, bias=False); self.bn1 = nn.BatchNorm1d(ch)
        self.res2  = Res2Conv1dReluBn(ch, scale, dil)
        self.conv3 = nn.Conv1d(ch, ch, 1, bias=False); self.bn3 = nn.BatchNorm1d(ch)
        self.se    = SEBlock1d(ch); self.relu = nn.ReLU(inplace=True)
```

#### 🔑 מתודה קריטית: `SERes2Block.forward`
```python
def forward(self, x, residual=None):
        if residual is None: residual = x
        o = self.relu(self.bn1(self.conv1(x)))
        o = self.res2(o)
        o = self.relu(self.bn3(self.conv3(o)))
        o = self.se(o)
        return o + residual
```

### 🏛️ מחלקה: `AttentivePool`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.tdnn` = `nn.Conv1d(C * 3, 128, 1)`
- `self.attn` = `nn.Conv1d(128, C, 1)`

**רשימת מתודות:** `__init__(self, C)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `AttentivePool.__init__`
```python
def __init__(self, C):
        super().__init__()
        self.tdnn = nn.Conv1d(C*3, 128, 1)
        self.attn = nn.Conv1d(128, C, 1)
```

#### 🔑 מתודה קריטית: `AttentivePool.forward`
```python
def forward(self, x):
        mu  = x.mean(2, keepdim=True).expand_as(x)
        sg  = x.var(2, keepdim=True).clamp(1e-4).sqrt().expand_as(x)
        ctx = torch.cat([x, mu, sg], 1)
        alpha = F.softmax(self.attn(torch.tanh(self.tdnn(ctx))), dim=2)
        mean  = (alpha * x).sum(2)
        std   = ((alpha * x.pow(2)).sum(2) - mean.pow(2) + 1e-4).sqrt()
        return torch.cat([mean, std], 1)
```

### 🏛️ מחלקה: `ECAPA_TDNN`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.stem` = `nn.Sequential(nn.Conv1d(80, C, 5, padding=2, bias=False), nn.BatchNorm1d(C), nn.ReLU(inpla…`
- `self.b1` = `SERes2Block(C, 2, scale)`
- `self.b2` = `SERes2Block(C, 3, scale)`
- `self.b3` = `SERes2Block(C, 4, scale)`
- `self.mfa` = `nn.Conv1d(C * 3, C * 3, 1)`
- `self.pool` = `AttentivePool(C * 3)`
- `self.bn_p` = `nn.BatchNorm1d(C * 6)`
- `self.fc` = `nn.Linear(C * 6, emb)`
- `self.bn_e` = `nn.BatchNorm1d(emb)`

**רשימת מתודות:** `__init__(self, C=512, emb=192, scale=8)`, `forward(self, x)`

#### 🔑 מתודה קריטית: `ECAPA_TDNN.__init__`
```python
def __init__(self, C=512, emb=192, scale=8):
        super().__init__()
        self.stem  = nn.Sequential(
            nn.Conv1d(80, C, 5, padding=2, bias=False),
            nn.BatchNorm1d(C), nn.ReLU(inplace=True))
        self.b1    = SERes2Block(C, 2, scale)
        self.b2    = SERes2Block(C, 3, scale)
        self.b3    = SERes2Block(C, 4, scale)
        self.mfa   = nn.Conv1d(C*3, C*3, 1)
        self.pool  = AttentivePool(C*3)
        self.bn_p  = nn.BatchNorm1d(C*6)
        self.fc    = nn.Linear(C*6, emb)
        self.bn_e  = nn.BatchNorm1d(emb)
```

#### 🔑 מתודה קריטית: `ECAPA_TDNN.forward`
```python
def forward(self, x):
        h  = self.stem(x)
        o1 = self.b1(h,  residual=h)
        o2 = self.b2(o1, residual=h+o1)
        o3 = self.b3(o2, residual=h+o1+o2)
        mfa   = self.mfa(torch.cat([o1, o2, o3], 1))
        stats = self.pool(mfa)
        emb   = self.bn_e(self.fc(self.bn_p(stats)))
        return F.normalize(emb, p=2, dim=1)
```

### 🔧 פונקציה עצמאית: `enroll(model: ECAPA_TDNN, speaker: str, wav_paths: list, n_segs: int=5)`
*Docstring:* Enroll a speaker by averaging embeddings across multiple utterances.
Overwrites any existing profile for this speaker.
Best practice: provide ≥5 diverse utterances (different mic / day / topic).
```python
def enroll(model: ECAPA_TDNN, speaker: str,
           wav_paths: list, n_segs: int = 5) -> None:
    """
    Enroll a speaker by averaging embeddings across multiple utterances.
    Overwrites any existing profile for this speaker.
    Best practice: provide ≥5 diverse utterances (different mic / day / topic).
    """
    if not wav_paths:
        print("❌ No WAV files provided."); return

    print(f"Enrolling '{speaker}' from {len(wav_paths)} utterance(s)...")
    all_embs = []
    for wav in wav_paths:
        if not os.path.exists(wav):
            print(f"  ⚠️  Missing: {wav}"); continue
        emb = extract_embedding(model, wav, n_segs=n_segs)
        all_embs.append(emb)
        print(f"  ✓  {os.path.basename(wav)}")

    if not all_embs:
        print("❌ No valid utterances."); return

    # Speaker d-vector = mean of all utterance embeddings, L2-normalised
    d_vector = F.normalize(torch.stack(all_embs).mean(0), p=2, dim=0)

    profiles = _load_profiles()
    profiles[speaker] = d_vector
    _save_profiles(profiles)
    print(f"✅ '{speaker}' enrolled ({len(all_embs)} utterances).")
```

### 🔧 פונקציה עצמאית: `identify(model: ECAPA_TDNN, wav_path: str, top_k: int=1, threshold: float=None, n_segs: int=5)`
*Docstring:* Identify speaker in wav_path.
Returns list of (speaker, cosine_score) sorted descending.
If threshold given, returns only results with score >= threshold.
```python
def identify(model: ECAPA_TDNN, wav_path: str,
             top_k: int = 1, threshold: float = None,
             n_segs: int = 5) -> list:
    """
    Identify speaker in wav_path.
    Returns list of (speaker, cosine_score) sorted descending.
    If threshold given, returns only results with score >= threshold.
    """
    profiles = _load_profiles()
    if not profiles:
        print("❌ No enrolled speakers. Run 'enroll' first."); return []

    emb = F.normalize(extract_embedding(model, wav_path, n_segs), p=2, dim=0)

    scores = {}
    for spk, dvec in profiles.items():
        scores[spk] = F.cosine_similarity(emb.unsqueeze(0),
                                          dvec.unsqueeze(0)).item()

    ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)

    if threshold is not None:
        ranked = [(s, c) for s, c in ranked if c >= threshold]

    return ranked[:top_k]
```

### 🔧 פונקציה עצמאית: `main()`
```python
def main():
    parser = argparse.ArgumentParser(
        description="12-Speaker Identification using ECAPA-TDNN")
    sub = parser.add_subparsers(dest='cmd', required=True)

    # enroll
    p_enroll = sub.add_parser('enroll', help='Enroll a speaker')
    p_enroll.add_argument('--speaker', required=True,
                          help='Speaker name (e.g. alice)')
    p_enroll.add_argument('--wavs',    required=True, nargs='+',
                          help='WAV files for this speaker')
    p_enroll.add_argument('--segs',    type=int, default=5,
                          help='Segments per utterance (default: 5)')

    # identify
    p_id = sub.add_parser('identify', help='Identify speaker in audio file')
    p_id.add_argument('--wav',       required=True, help='Audio file to identify')
    p_id.add_argument('--top',       type=int,   default=1,
                      help='Return top-k candidates (default: 1)')
    p_id.add_argument('--threshold', type=float, default=None,
                      help='Minimum cosine score (0–1). No filter by default.')
    p_id.add_argument('--segs',      type=int,   default=5,
                      help='Segments per utterance (default: 5)')

    # list
    sub.add_parser('list', help='List enrolled speakers')

    # remove
    p_rm = sub.add_parser('remove', help='Remove enrolled speaker')
    p_rm.add_argument('--speaker', required=True)

    args = parser.parse_args()

    # Load model (shared across all commands)
    if not os.path.exists(MODEL_PATH):
        print(f"❌ Model not found: {MODEL_PATH}")
        print("   Copy best_model.pt from Google Drive to this directory.")
        sys.exit(1)

    model = load_model(MODEL_PATH)

    if args.cmd == 'enroll':
        enroll(model, args.speaker, args.wavs, n_segs=args.segs)

    elif args.cmd == 'identify':
        results = identify(model, args.wav,
                           top_k=args.top,
                           threshold=args.threshold,
                           n_segs=args.segs)
        if not results:
            print("No match found.")
        else:
            print(f"\nIdentification result for: {os.path.basename(args.wav)}")
            for rank, (spk, score) in enumerate(results, 1):
                bar = '█' * int(score * 20)
                print(f"  #{rank}  {spk:<20}  score={score:.4f}  {bar}")
            # Decision
            top_spk, top_score = results[0]
            if args.threshold and top_score < args.threshold:
                print(f"\n  → UNKNOWN (best score {top_score:.4f} < threshold {args.threshold:.2f})")
    # ... [קוצר — עוד 8 שורות בקובץ המקורי] ...
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`speaker_id_inference.py` (30 נמצאו):
```
L    5 [.wav]  python speaker_id_inference.py enroll  --speaker alice  --wavs audio/alice/*.wav
L    6 [.wav]  python speaker_id_inference.py enroll  --speaker bob    --wavs audio/bob/*.wav
L    7 [.wav]  python speaker_id_inference.py identify --wav  audio/unknown.wav
L    8 [.wav]  python speaker_id_inference.py identify --wav  audio/unknown.wav --top 3
L   15 [soundfile]  import soundfile as sf
L   22 [best_model]  MODEL_PATH = r"core\weights\best_model.pt"
L   23 [embedding]  PROFILE_FILE = r"..\shared_data\embeddings_backup\speaker_profiles.json"
L   35 [16_000]  _SR  = 16_000
L  203 [torch.load]  ckpt  = torch.load(model_path, map_location='cpu')
L  204 [checkpoint]  # Checkpoint may be state_dict directly or wrapped
L  206 [state_dict]  model.load_state_dict(state)
L  212 [embedding]  def extract_embedding(model: ECAPA_TDNN, wav_path: str,
L  214 [embedding]  """Extract a single L2-normalised 192-dim embedding for one audio file."""
L  226 [embedding]  Enroll a speaker by averaging embeddings across multiple utterances.
L  238 [embedding]  emb = extract_embedding(model, wav, n_segs=n_segs)
L  245 [embedding]  # Speaker d-vector = mean of all utterance embeddings, L2-normalised
L  255 [threshold]  top_k: int = 1, threshold: float = None,
L  260 [threshold]  If threshold given, returns only results with score >= threshold.
L  266 [embedding]  emb = F.normalize(extract_embedding(model, wav_path, n_segs), p=2, dim=0)
L  270 [cosine_similarity]  scores[spk] = F.cosine_similarity(emb.unsqueeze(0),
L  275 [threshold]  if threshold is not None:
L  276 [threshold]  ranked = [(s, c) for s, c in ranked if c >= threshold]
L  321 [threshold]  p_id.add_argument('--threshold', type=float, default=None,
L  338 [best_model]  print("   Copy best_model.pt from Google Drive to this directory.")
L  344 [.wav]  enroll(model, args.speaker, args.wavs, n_segs=args.segs)
L  347 [.wav]  results = identify(model, args.wav,
L  349 [threshold]  threshold=args.threshold,
L  354 [.wav]  print(f"\nIdentification result for: {os.path.basename(args.wav)}")
L  360 [threshold]  if args.threshold and top_score < args.threshold:
L  361 [threshold]  print(f"\n  → UNKNOWN (best score {top_score:.4f} < threshold {args.threshold:.2f})")
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\core\model.py`

**Docstring של הקובץ:**
```
ai_engine/core/model.py
═══════════════════════════════════════════════════════════════════
ECAPA-TDNN — ארכיטקטורה מדויקת לפי best_model.pt (192 שכבות)
נגזרת ישירות מניתוח checkpoint בסקריפט inspect_checkpoint.py.

פרמטרים שאומנו: C=512, emb=192, scale=8
קלט:  [Batch, 80, T]   — 80-dim Fbank filterbank features
פלט:  [Batch, 192]     — L2-normalised speaker embedding

מבנה:
  stem       → Conv1d(80→512, k=5, bias=False) + BN
  block1–3   → SERes2Block(C=512, scale=8)
  mfa_conv   → Conv1d(1536→1536, k=1, bias=True)
  pooling    → AttentivePool(1536) → [B, 3072]
  bn_pool    → BN(3072)
  fc_em
```


### 🏛️ מחלקה: `SEBlock`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.fc` = `nn.Sequential(nn.Linear(C, C // 4, bias=False), nn.ReLU(), nn.Linear(C // 4, C, bias=False…`

**רשימת מתודות:** `__init__(self, C: int=512)`, `forward(self, x: torch.Tensor)`

#### 🔑 מתודה קריטית: `SEBlock.__init__`
```python
def __init__(self, C: int = 512):
        super().__init__()
        self.fc = nn.Sequential(
            nn.Linear(C, C // 4, bias=False),   # fc.0
            nn.ReLU(),                            # fc.1
            nn.Linear(C // 4, C, bias=False),   # fc.2
            nn.Sigmoid(),                         # fc.3
        )
```

#### 🔑 מתודה קריטית: `SEBlock.forward`
```python
def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, C, T]
        s = x.mean(dim=-1)          # [B, C] — global avg pool
        s = self.fc(s)              # [B, C]
        return x * s.unsqueeze(-1)
```

### 🏛️ מחלקה: `Res2Conv1d`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.scale` = `scale`
- `self.convs` = `nn.ModuleList([nn.Conv1d(width, width, kernel_size, padding=kernel_size // 2, bias=False) …`
- `self.bns` = `nn.ModuleList([nn.BatchNorm1d(width) for _ in range(n)])`

**רשימת מתודות:** `__init__(self, C: int=512, scale: int=8, kernel_size: int=3)`, `forward(self, x: torch.Tensor)`

#### 🔑 מתודה קריטית: `Res2Conv1d.__init__`
```python
def __init__(self, C: int = 512, scale: int = 8, kernel_size: int = 3):
        super().__init__()
        assert C % scale == 0, f"C={C} must be divisible by scale={scale}"
        self.scale = scale
        width = C // scale          # 512 // 8 = 64
        n = scale - 1               # 7 branches

        self.convs = nn.ModuleList([
            nn.Conv1d(width, width, kernel_size,
                      padding=kernel_size // 2, bias=False)
            for _ in range(n)
        ])
        self.bns = nn.ModuleList([
            nn.BatchNorm1d(width) for _ in range(n)
        ])
```

#### 🔑 מתודה קריטית: `Res2Conv1d.forward`
```python
def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, C, T]  →  split into scale chunks of width channels
        chunks = x.chunk(self.scale, dim=1)
        outs = []
        y = None
        for i, chunk in enumerate(chunks):
            if i == 0:
                # first chunk: identity (no conv)
                outs.append(chunk)
            elif y is None:
                # second chunk: first conv branch, no accumulation
                y = F.relu(self.bns[i - 1](self.convs[i - 1](chunk)))
                outs.append(y)
            else:
                # remaining chunks: add previous branch output
                y = F.relu(self.bns[i - 1](self.convs[i - 1](chunk + y)))
                outs.append(y)
        return torch.cat(outs, dim=1)
```

### 🏛️ מחלקה: `SERes2Block`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.conv1` = `nn.Conv1d(C, C, 1, bias=False)`
- `self.bn1` = `nn.BatchNorm1d(C)`
- `self.res2` = `Res2Conv1d(C, scale=scale)`
- `self.conv3` = `nn.Conv1d(C, C, 1, bias=False)`
- `self.bn3` = `nn.BatchNorm1d(C)`
- `self.se` = `SEBlock(C)`

**רשימת מתודות:** `__init__(self, C: int=512, scale: int=8)`, `forward(self, x: torch.Tensor)`

#### 🔑 מתודה קריטית: `SERes2Block.__init__`
```python
def __init__(self, C: int = 512, scale: int = 8):
        super().__init__()
        self.conv1 = nn.Conv1d(C, C, 1, bias=False)
        self.bn1   = nn.BatchNorm1d(C)
        self.res2  = Res2Conv1d(C, scale=scale)
        self.conv3 = nn.Conv1d(C, C, 1, bias=False)
        self.bn3   = nn.BatchNorm1d(C)
        self.se    = SEBlock(C)
```

#### 🔑 מתודה קריטית: `SERes2Block.forward`
```python
def forward(self, x: torch.Tensor) -> torch.Tensor:
        residual = x
        out = F.relu(self.bn1(self.conv1(x)))
        out = self.res2(out)
        out = F.relu(self.bn3(self.conv3(out)))
        out = self.se(out)
        return out + residual
```

### 🏛️ מחלקה: `AttentivePool`  (יורשת מ-`nn.Module`)

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.tdnn` = `nn.Conv1d(C * 3, 128, 1)`
- `self.attn` = `nn.Conv1d(128, C, 1)`

**רשימת מתודות:** `__init__(self, C: int=1536)`, `forward(self, x: torch.Tensor)`

#### 🔑 מתודה קריטית: `AttentivePool.__init__`
```python
def __init__(self, C: int = 1536):
        super().__init__()
        self.tdnn = nn.Conv1d(C * 3, 128, 1)
        self.attn = nn.Conv1d(128, C, 1)
```

#### 🔑 מתודה קריטית: `AttentivePool.forward`
```python
def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, 1536, T]
        B, C, T = x.shape

        # tile global mean and std to match time dimension
        mean = x.mean(dim=-1, keepdim=True).expand(-1, -1, T)  # [B, C, T]
        std  = x.std(dim=-1, keepdim=True).expand(-1, -1, T)   # [B, C, T]

        feat = torch.cat([x, mean, std], dim=1)                  # [B, C*3=4608, T]
        h    = torch.relu(self.tdnn(feat))                        # [B, 128, T]
        a    = torch.softmax(self.attn(h), dim=-1)                # [B, C=1536, T]

        # weighted statistics
        mu  = (a * x).sum(dim=-1)                                 # [B, C]
        rss = (a * x.pow(2)).sum(dim=-1)
        sg  = (rss - mu.pow(2)).clamp(min=1e-8).sqrt()            # [B, C]

        return torch.cat([mu, sg], dim=1)
```

### 🏛️ מחלקה: `ECAPA_TDNN`  (יורשת מ-`nn.Module`)

**Docstring:**
```
ECAPA-TDNN(C=512, emb=192, scale=8)
תואמת בדיוק ל-best_model.pt — אין שגיאות load_state_dict.
```

**משתני המחלקה (self.X שמוגדרים ב-`__init__`):**
- `self.stem` = `nn.Sequential(nn.Conv1d(80, C, kernel_size=5, padding=2, bias=False), nn.BatchNorm1d(C))`
- `self.block1` = `SERes2Block(C, scale)`
- `self.block2` = `SERes2Block(C, scale)`
- `self.block3` = `SERes2Block(C, scale)`
- `self.mfa_conv` = `nn.Conv1d(C * 3, C * 3, 1)`
- `self.pooling` = `AttentivePool(C * 3)`
- `self.bn_pool` = `nn.BatchNorm1d(C * 6)`
- `self.fc_embed` = `nn.Linear(C * 6, emb)`
- `self.bn_embed` = `nn.BatchNorm1d(emb)`

**רשימת מתודות:** `__init__(self, C: int=512, emb: int=192, scale: int=8)`, `forward(self, x: torch.Tensor)`

#### 🔑 מתודה קריטית: `ECAPA_TDNN.__init__`
```python
def __init__(self, C: int = 512, emb: int = 192, scale: int = 8):
        super().__init__()

        # stem: Conv1d(80→C, k=5, bias=False) + BN
        # checkpoint: stem.0.weight [512,80,5]  (NO bias → bias=False)
        #             stem.1.*      BN(512)
        self.stem = nn.Sequential(
            nn.Conv1d(80, C, kernel_size=5, padding=2, bias=False),
            nn.BatchNorm1d(C),
        )

        # 3 × SERes2Block
        self.block1 = SERes2Block(C, scale)
        self.block2 = SERes2Block(C, scale)
        self.block3 = SERes2Block(C, scale)

        # Multi-scale Feature Aggregation conv
        # checkpoint: mfa_conv.weight [1536,1536,1]  mfa_conv.bias [1536]
        self.mfa_conv = nn.Conv1d(C * 3, C * 3, 1)   # bias=True (default)

        # Attentive Pooling
        self.pooling = AttentivePool(C * 3)            # C*3=1536 → [B, 3072]

        # Embedding layers
        # checkpoint: bn_pool.weight [3072]
        #             fc_embed.weight [192,3072]  fc_embed.bias [192]
        #             bn_embed.weight [192]
        self.bn_pool  = nn.BatchNorm1d(C * 6)         # 3072
        self.fc_embed = nn.Linear(C * 6, emb)         # bias=True
        self.bn_embed = nn.BatchNorm1d(emb)
```

#### 🔑 מתודה קריטית: `ECAPA_TDNN.forward`
*Docstring:* x: [B, 80, T]  — Fbank features
returns: [B, 192] — L2-normalised embedding
```python
def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        x: [B, 80, T]  — Fbank features
        returns: [B, 192] — L2-normalised embedding
        """
        # Stem
        x = F.relu(self.stem(x))       # [B, 512, T]

        # Res2 blocks
        e1 = self.block1(x)            # [B, 512, T]
        e2 = self.block2(e1)           # [B, 512, T]
        e3 = self.block3(e2)           # [B, 512, T]

        # Multi-scale aggregation
        cat = torch.cat([e1, e2, e3], dim=1)   # [B, 1536, T]
        cat = F.relu(self.mfa_conv(cat))        # [B, 1536, T]

        # Attentive pooling → fixed-size embedding
        out = self.pooling(cat)                 # [B, 3072]
        out = self.bn_pool(out)                 # [B, 3072]
        out = self.fc_embed(out)                # [B, 192]
        out = self.bn_embed(out)                # [B, 192]

        # L2 normalise → unit sphere
        return F.normalize(out, p=2, dim=1)
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`model.py` (13 נמצאו):
```
L    4 [best_model]  ECAPA-TDNN — ארכיטקטורה מדויקת לפי best_model.pt (192 שכבות)
L    5 [checkpoint]  נגזרת ישירות מניתוח checkpoint בסקריפט inspect_checkpoint.py.
L    9 [embedding]  פלט:  [Batch, 192]     — L2-normalised speaker embedding
L   34 [checkpoint]  # checkpoint: se.fc.0.weight [128,512]  se.fc.2.weight [512,128]
L   56 [checkpoint]  # checkpoint: res2.convs.N.weight [64,64,3]  (no bias)
L  130 [checkpoint]  # checkpoint:
L  166 [state_dict]  תואמת בדיוק ל-best_model.pt — אין שגיאות load_state_dict.
L  172 [checkpoint]  # checkpoint: stem.0.weight [512,80,5]  (NO bias → bias=False)
L  185 [checkpoint]  # checkpoint: mfa_conv.weight [1536,1536,1]  mfa_conv.bias [1536]
L  191 [embedding]  # Embedding layers
L  192 [checkpoint]  # checkpoint: bn_pool.weight [3072]
L  202 [embedding]  returns: [B, 192] — L2-normalised embedding
L  216 [embedding]  # Attentive pooling → fixed-size embedding
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\core\pipeline.py`

**קבועים גלובליים (Hyperparameters):**
```python
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
VAD_MODEL_PATH = os.path.join(BASE_DIR, 'weights', 'silero_vad_offline.jit')
```


### 🔧 פונקציה עצמאית: `_rms_normalize(audio: np.ndarray, target_rms: float=0.1)`
*Docstring:* מנרמל את עוצמת הקול לרמה אחידה.
פותר את הבעיה שבה משתמש אחד לוחש והשני צועק.
```python
def _rms_normalize(audio: np.ndarray, target_rms: float = 0.1) -> np.ndarray:
    """
    מנרמל את עוצמת הקול לרמה אחידה.
    פותר את הבעיה שבה משתמש אחד לוחש והשני צועק.
    """
    rms = np.sqrt(np.mean(audio ** 2))
    if rms > 1e-6:
        return (audio / rms * target_rms).astype(np.float32)
    return audio
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`pipeline.py` (8 נמצאו):
```
L   42 [sample_rate]  def run_vad(audio_np: np.ndarray, sample_rate: int = 16000, threshold: float = 0.5) -> np.ndarray:
L   60 [sample_rate]  prob = model(chunk.unsqueeze(0), sample_rate).item()
L   62 [threshold]  if prob > threshold:
L   66 [EER]  raise ValueError("No speech detected in audio.")
L   79 [s_norm]  def _rms_normalize(audio: np.ndarray, target_rms: float = 0.1) -> np.ndarray:
L   92 [16000]  def process_audio_pipeline(audio_np: np.ndarray, target_sr: int = 16000, T_fixed: int = 300) -> np.ndarray:
L   98 [EER]  raise RuntimeError("C++ module 'speaker_features' is missing. Cannot process audio.")
L  101 [s_norm]  audio_np = _rms_normalize(audio_np)
```