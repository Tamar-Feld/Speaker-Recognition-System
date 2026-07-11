import os
import sys
import json
import math
import uuid
import tempfile
import traceback
import subprocess
import numpy as np
import soundfile as _sf
import librosa
import torch
import torch.nn as nn
import torch.nn.functional as F
from contextlib import asynccontextmanager
from fastapi import FastAPI, File, UploadFile, HTTPException, Form, Depends
import imageio_ffmpeg
import uvicorn
from silero_vad import get_speech_timestamps as _vad_timestamps
from silero_vad import collect_chunks as _vad_collect
import threading
import secrets
from fastapi import Header
AI_API_KEY = "5b61d4ae1aa4cbd5943b4d3876dc8e72a470bdc5f60be7675d4a31c8ad9befb8"

def verify_api_key(x_api_key: str = Header(...)):
    if not secrets.compare_digest(x_api_key, AI_API_KEY):
        raise HTTPException(status_code=403, detail="Forbidden")

# ── Module paths ──────────────────────────────────────────────────────────────
_AI_ENGINE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_AI_ENGINE_DIR, "dsp_module"))

from core.model import ECAPA_TDNN
import speaker_features as _spk_feat

# ── Audio constants ───────────────────────────────────────────────────────────
_SR             = 16_000
_TARGET_SAMPLES = int(3.0 * _SR)                            # 48 000 samples
_N_FFT          = 512
_HOP            = 160
_FIXED_T        = (_TARGET_SAMPLES - _N_FFT) // _HOP + 1   # 297 frames
_RMS_TARGET     = 0.1                                        # RMS amplitude target
# ── VAD constants & state ─────────────────────────────────────────────────────
# 0.30 is a starting placeholder — calibrate empirically on real user recordings.
SPEECH_RATIO_MIN = 0.30
_vad_model       = None
_vad_lock = threading.Lock()
# ── App & model state ─────────────────────────────────────────────────────────
torch.set_num_threads(1)
device          = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model: ECAPA_TDNN = None
weights_loaded  = False

# ── Startup: load ECAPA-TDNN weights into RAM ─────────────────────────────────
def _load_model():
    global model, weights_loaded, _vad_model
    print("🚀 [AI Server] Starting up...")
    model = ECAPA_TDNN(C=512, emb=192, scale=8) #לכאן נשלח הקלט של ערוצי מל שחולצו ישר לforward של המחלקה ECAPA TDNN בקוד המודל
    model_path = os.path.join(_AI_ENGINE_DIR, "core", "weights", "best_model.pt")
    if not os.path.exists(model_path):
        print(f"❌ [AI Server] Model weights not found: {model_path}")
    else:
        try:#מטעינים את המשקולות במודל
            ckpt  = torch.load(model_path, map_location=device, weights_only=True)
            state = ckpt.get("model", ckpt.get("state_dict", ckpt)) if isinstance(ckpt, dict) else ckpt
            model.load_state_dict(state)
            weights_loaded = True
            print(f"✅ [AI Server] Weights loaded: {model_path}") ###
        except Exception as e:
            print(f"❌ [AI Server] Error loading weights: {e}")
    model.to(device)
    model.eval()#לא משנים את המשקולות נועלים אותן
    print("Stateless mode: Java sends all enrolled embeddings on every /predict request.")
    # Load Silero VAD from local .jit (offline — no internet required at runtime)
    _vad_path = os.path.join(_AI_ENGINE_DIR, "core", "weights", "silero_vad_offline.jit")
    if os.path.exists(_vad_path):#טוענים את מודל הVAD
        try:
            _vad_model = torch.jit.load(_vad_path, map_location="cpu")
            _vad_model.eval()
            print(f"✅ [AI Server] Silero VAD loaded: {_vad_path}")
        except Exception as _e:
            print(f"⚠️  [AI Server] VAD load failed: {_e} — VAD disabled, audio accepted as-is")
            _vad_model = None
    else:
        print(f"⚠️  [AI Server] VAD model not found at {_vad_path} — VAD disabled")
@asynccontextmanager
async def _lifespan(app: FastAPI):
    _load_model()
    yield
app = FastAPI(title="SpeakerAuth AI Engine", lifespan=_lifespan)

# ── Audio Format Converter (WebM/Ogg -> WAV 16kHz Mono) ───────────────────────
def convert_to_wav_mono_16khz(incoming_file: UploadFile) -> str:
    temp_dir = tempfile.gettempdir()
    temp_input = os.path.join(temp_dir, f"raw_audio_{uuid.uuid4().hex}.webm")
    temp_output = os.path.join(temp_dir, f"converted_audio_{uuid.uuid4().hex}.wav")
    try:
        # Write the raw bytes from FastAPI UploadFile to disk
        with open(temp_input, "wb") as f:
            f.write(incoming_file.file.read())
        # Get the internal FFmpeg executable path
        ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
        # Build the FFmpeg command
        command = [
            ffmpeg_exe,
            "-y",                  # Overwrite if exists
            "-i", temp_input,      # Input file
            "-ac", "1",            # Force 1 channel (mono)
            "-ar", "16000",        # Force 16000Hz sample rate
            "-loglevel", "error",  # Hide noisy logs
            temp_output            # Output file
        ]
        # Run FFmpeg process
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode != 0:
            error_msg = result.stderr.decode('utf-8', errors='ignore')
            raise Exception(f"FFmpeg conversion failed: {error_msg}")
        return temp_output
    except Exception as e:
        if os.path.exists(temp_input):
            try: os.remove(temp_input)
            except OSError: pass
        if os.path.exists(temp_output):
            try: os.remove(temp_output)
            except OSError: pass
        raise HTTPException(status_code=400, detail=f"Audio conversion failed: {str(e)}")
    finally:
        # Clean up the raw input file immediately
        if os.path.exists(temp_input):
            try: os.remove(temp_input)
            except OSError: pass

# ── VAD helper ───────────────────────────────────────────────────────────────
def _apply_vad(audio: np.ndarray) -> tuple:
    if _vad_model is None:
        return audio, 1.0
    audio_t    = torch.from_numpy(audio).float()
    with _vad_lock:
        timestamps = _vad_timestamps(
            audio_t, _vad_model,
            sampling_rate=_SR,
            threshold=0.5,
            min_silence_duration_ms=100,
            speech_pad_ms=30,
            min_speech_duration_ms=250,
        )
    if not timestamps:
        return audio, 0.0
    speech_t = _vad_collect(timestamps, audio_t)
    ratio    = len(speech_t) / max(len(audio_t), 1)
    return speech_t.numpy(), float(ratio)

# ── Feature extraction ────────────────────────────────────────────────────────
def _audio_to_tensor(audio_src, n_segs: int = 5, target_samples: int = _TARGET_SAMPLES) -> torch.Tensor:
    if hasattr(audio_src, "seek"): audio_src.seek(0)
    audio, sr = _sf.read(audio_src)
    if audio.ndim > 1: audio = audio.mean(axis=1)
    if sr != _SR: audio = librosa.resample(audio.astype(np.float32), orig_sr=sr, target_sr=_SR)
    audio = audio.astype(np.float32)
    # VAD: strip silence before normalization (per project spec)
    audio, _ratio = _apply_vad(audio)
    if _ratio < SPEECH_RATIO_MIN:
        raise ValueError(f"SPEECH_RATIO_TOO_LOW:{_ratio:.3f}")
    # RMS normalization למה הנרמול הזה להסביר
    _rms = np.sqrt(np.mean(audio ** 2))
    if _rms > 1e-6:
        audio = (audio / _rms * _RMS_TARGET).astype(np.float32)
    if len(audio) < target_samples:
        reps  = math.ceil(target_samples / len(audio))
        audio = np.tile(audio, reps)[:target_samples]
    n_segs = max(1, min(n_segs, len(audio) // target_samples + 1))
    if n_segs == 1: starts = [0]
    else:
        step   = (len(audio) - target_samples) / (n_segs - 1)
        starts = list(dict.fromkeys(int(round(i * step)) for i in range(n_segs)))
    segs = []
    for s in starts:
        seg  = audio[s: s + target_samples]
        feat = _spk_feat.extract(np.ascontiguousarray(seg, dtype=np.float32))
        feat = ((feat - feat.mean(1, keepdims=True)) / (feat.std(1, keepdims=True) + 1e-5))
        T = feat.shape[1]
        if   T > _FIXED_T: feat = feat[:, :_FIXED_T]
        elif T < _FIXED_T: feat = np.pad(feat, ((0, 0), (0, _FIXED_T - T)))
        segs.append(feat)
    return torch.tensor(np.stack(segs), dtype=torch.float32)

def extract_embedding(audio_src, n_segs: int = 5) -> torch.Tensor:
    segs = _audio_to_tensor(audio_src, n_segs=n_segs)
    segs = segs.to(device)
    # המודל (model) נטען בסטארטאפ ומבצע חישוב מטריצות (משקולות * קלט)
    with torch.no_grad():
        embs = model(segs)
    return embs.mean(0).cpu()

# ── POST /enroll ──────────────────────────────────────────────────────────────
@app.post("/enroll", dependencies=[Depends(verify_api_key)])
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
    except ValueError as e:
        err = str(e)
        if "SPEECH_RATIO_TOO_LOW" in err:
            ratio = float(err.split(":")[1])
            print(f"⚠️  [AI Server] Enrollment rejected — speech ratio {ratio:.1%} < {SPEECH_RATIO_MIN:.1%}")
            raise HTTPException(status_code=422, detail={
                "code": "SPEECH_RATIO_TOO_LOW",
                "ratio": round(ratio, 3),
                "minimum": SPEECH_RATIO_MIN
            })
        raise HTTPException(status_code=400, detail=f"Audio processing failed: {err}")
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

# ── POST /predict ─────────────────────────────────────────────────────────────
@app.post("/predict", dependencies=[Depends(verify_api_key)])
def predict_speaker(file: UploadFile = File(...), embeddings: str = Form(...)):
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
    except ValueError as e:
        err = str(e)
        if "SPEECH_RATIO_TOO_LOW" in err:
            ratio = float(err.split(":")[1])
            print(f"⚠️  [AI Server] Predict rejected — speech ratio {ratio:.1%} < {SPEECH_RATIO_MIN:.1%}")
            raise HTTPException(status_code=422, detail={
                "code": "SPEECH_RATIO_TOO_LOW",
                "ratio": round(ratio, 3),
                "minimum": SPEECH_RATIO_MIN
            })
        raise HTTPException(status_code=400, detail=f"Audio processing failed: {err}")
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

# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    uvicorn.run("api:app", host="127.0.0.1", port=8000, reload=False)