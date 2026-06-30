# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**SpeakerAuth** is a speaker identification and biometric access control system built with a microservices architecture. It enables secure room access through voice authentication using the ECAPA-TDNN deep learning model.

The system comprises three independent services that communicate via REST APIs:
1. **React Client** (port 3000) — Interactive UI for access control and administration
2. **Spring Boot Server** (port 8080) — REST gateway, business logic, database persistence
3. **FastAPI AI Engine** (port 8000) — Speaker identification and biometric enrollment

All services run independently; the system is designed for zero disk I/O on audio processing (RAM-based streaming).

---

## Architecture

### High-Level Data Flow

```
User Audio Input
    ↓
React Client (useAudioRecorder hook)
    ↓
POST /api/audio/upload (Spring Boot)
    ↓
AudioController: Business Logic & Permissions Check
    ↓
POST /predict (FastAPI)
    ↓
ECAPA-TDNN Model + Cosine Similarity Matching
    ↓
Speaker Identity + Confidence Score
    ↓
Access Decision (Speaker registered? Has room permission? Confidence > 60%?)
    ↓
AccessLog saved to MySQL + Response to React
    ↓
Door Animation + UX Feedback
```

### Service Architecture

#### **React Client** (`/client`)
- **Framework:** Vite + React 18 with TailwindCSS
- **Pages:**
  - `CorridorPage` — Main lobby with 6 door buttons and brief access flow
  - `DoorPage` — Individual room with detailed access panel, 3D door animation, microphone recording
  - `LoginPage` — Admin authentication (JWT-based, stored in sessionStorage)
  - `AdminPage` — Dashboard for user management, room permissions, and access logs
- **Hooks:**
  - `useAudioRecorder` — Browser Web Audio API recording, auto-stop at 4 seconds
  - `useAdminAuthContext` (AdminAuthContext) — Admin JWT session persistence and logout
- **Services:**
  - `api.js` — Axios wrapper for all REST calls to Spring Boot (`http://localhost:8080/api/*`)
- **Build:** `npm run dev` (Vite dev server), `npm run build` (production)
- **Styling:** Inline CSS-in-JS (not external files); uses theme with gold/cyan accents for futuristic UI

#### **Spring Boot Server** (`/server`)
- **Framework:** Spring Boot 3.4.12 with Maven
- **Database:** MySQL (auto-created schema via Hibernate `ddl-auto=update`)
- **Key Entities:**
  - `User` — Username, authorization status, biometric embedding (nullable)
  - `AccessLog` — Records each access attempt (filename, speaker, confidence, approval, room, timestamp)
  - `RoomPermission` — Join table mapping users to rooms (1–6)
- **Controllers:**
  - `AudioController` — Receives audio file + roomId, calls FastAPI `/predict`, enforces business logic (confidence threshold 60%, user authorization, room permissions), saves AccessLog, returns access decision
  - `UserController` — User CRUD, enrollment trigger (calls FastAPI `/enroll`), room permission toggles
  - `AccessLogController` — Query and export audit trail
- **Database Config:** `application.properties` defines MySQL connection, paths, and AI server URLs
- **Build:** `mvn clean install` or `mvnw spring-boot:run` (includes wrapper)
- **Key Configuration:**
  ```properties
  spring.datasource.url=jdbc:mysql://localhost:3306/speaker_db
  spring.datasource.username=root
  spring.datasource.password=1234
  upload.dir=C:\Users\WIN 11\Documents\SpeakerAuth\shared_data\enrollments
  ai.server.url=http://127.0.0.1:8000/predict
  ai.enroll.url=http://127.0.0.1:8000/enroll
  ```

#### **FastAPI AI Engine** (`/ai_engine`)
- **Framework:** FastAPI + Uvicorn (Python 3.8+)
- **Model:** ECAPA-TDNN (C=512 channels, 192-dim embedding)
  - Weights: `core/weights/best_model.pt` (24.8 MB)
  - Loaded into RAM on startup with `weights_only=True` (PyTorch 2.x safety)
  - Outputs L2-normalized 192-dim speaker embeddings
- **DSP Pipeline:**
  - `speaker_features.pyd` (C++ compiled module via PyBind11) — Fast Fbank extraction (80 mel-filterbank channels, 297 time frames)
  - VAD (Voice Activity Detection) via `silero_vad_offline.jit` — Removes silence before feature extraction
  - Per-channel z-score normalization before model inference
- **Prototypes Storage:** Joblib pickle file at `shared_data/embeddings_backup/prototypes.pkl`
  - Maps speaker username → L2-normalized 192-dim embedding (averaged across enrollment samples)
- **Endpoints:**
  - `POST /predict` — Single audio file → speaker identity + confidence (cosine similarity)
  - `POST /enroll` — Multiple audio samples (≥3) → compute prototype, save to pkl
- **Audio Specs:** Expects 16 kHz mono WAV; auto-resamples if different
- **Build/Run:** `python ai_engine/api.py` (uvicorn on port 8000)

---

## Database Schema

**MySQL Database:** `speaker_db`

### Tables (auto-created)
```sql
users
  - id (PK)
  - username (UNIQUE)
  - full_name
  - is_authorized (boolean)
  - created_at
  - biometric_embedding (TEXT, nullable)

access_logs
  - id (PK)
  - filename
  - timestamp
  - identified_speaker
  - confidence (double 0–1)
  - access_granted (boolean)
  - room_number (int)
  - ip_address
  - processing_time_ms
  - rejection_reason

room_permissions
  - id (PK)
  - username (FK)
  - room_number (int 1–6)
```

---

## Common Development Tasks

### Running the Full System

**All services at once** (Windows batch script):
```batch
cd C:\Users\WIN 11\Documents\SpeakerAuth
start_all.bat
```
Opens 3 terminal windows:
- Spring Boot: http://localhost:8080
- FastAPI: http://localhost:8000
- React: http://localhost:3000

**Individual services:**

**React Client:**
```bash
cd client
npm install  # First time only
npm run dev
# Opens http://localhost:3000
```

**Spring Boot:**
```bash
cd server
mvn clean install  # First time (fetches deps)
mvnw spring-boot:run
# Or: ./mvnw spring-boot:run (Unix/Mac)
```

**FastAPI AI Engine:**
```bash
cd ai_engine
# Ensure best_model.pt and silero_vad_offline.jit exist in core/weights/
python api.py
```

### Build for Production

**React:**
```bash
npm run build       # Outputs: dist/
npm run preview     # Local preview of production build
```

**Spring Boot:**
```bash
mvn clean package   # Outputs: server/target/SpeakerAuth-0.0.1-SNAPSHOT.jar
java -jar target/SpeakerAuth-0.0.1-SNAPSHOT.jar  # Run JAR
```

**FastAPI:**
No additional build needed; runs directly with `python api.py`.

### Linting & Code Quality

**React:**
```bash
npm run lint  # ESLint (checks client/src)
```

**Spring Boot:**
No built-in lint command; Maven handles compilation with javac.

**Python (AI Engine):**
No built-in lint; consider adding `flake8` or `black` for code style.

### Testing

**React:** No test suite currently implemented.
**Spring Boot:** No test suite currently implemented (stubs exist in pom.xml).
**FastAPI:** No unit tests; manual testing via curl or Postman.

### Common Workflows

**Register a new user with voice samples:**
1. Admin panel → "Register" form
2. Submit username + 3+ audio files
3. Spring Boot calls FastAPI `/enroll` endpoint
4. Prototype saved to `shared_data/embeddings_backup/prototypes.pkl`
5. User appears in users list with authorization status

**Grant room access to user:**
1. Admin panel → Expand user row
2. Click room chips (1–6) to toggle permissions
3. Spring Boot updates `room_permissions` table

**Test speaker identification:**
1. Go to any door (e.g., `/door/1`)
2. Click "IDENTIFY" button
3. Record voice for 4 seconds (auto-stops)
4. FastAPI `/predict` compares against enrolled prototypes
5. Door opens if: confidence > 60% AND user authorized AND has room permission

---

## Key Configuration Files

| File | Purpose |
|------|---------|
| `client/package.json` | React dependencies, build scripts |
| `client/vite.config.ts` | Vite build config (dev server port 3000) |
| `server/pom.xml` | Maven dependencies, Java 21, Spring Boot 3.4.12 |
| `server/src/main/resources/application.properties` | Database, AI server URLs, upload directory |
| `ai_engine/api.py` | FastAPI startup, model loading, `/predict` and `/enroll` endpoints |
| `ai_engine/core/model.py` | ECAPA-TDNN architecture definition |
| `ai_engine/core/pipeline.py` | Audio preprocessing (VAD, normalization) |
| `shared_data/embeddings_backup/prototypes.pkl` | Persistent speaker prototypes (created at runtime) |

---

## Critical Implementation Details

### Confidence Score Normalization
- **FastAPI returns:** 0–100 (percentage)
- **Spring Boot normalizes:** Divides by 100 → 0–1 (stored in DB, sent to React)
- **React displays:** Multiplies by 100 for percentage UI
- **Threshold:** Confidence (0–1 scale) must be ≥ 0.6 (60%) to grant access

### Audio Processing Pipeline
1. Browser records mono WAV via `useAudioRecorder`
2. Blob sent as `multipart/form-data` to Spring Boot
3. Spring Boot streams to FastAPI (no disk write)
4. FastAPI `_read_audio()` handles:
   - Stereo → mono conversion
   - Auto-resample to 16 kHz (if needed)
5. `_to_embedding()` then:
   - Volume normalization (divide by max amplitude)
   - Tile if < 3 seconds, center-crop to exactly 48,000 samples
   - C++ Fbank extraction → [80, 297] feature matrix
   - Z-score normalization per channel
   - Forward through ECAPA-TDNN → [192] L2-normalized embedding
   - Cosine similarity against all prototypes

### Prototype Storage
- Computed as **mean of enrollment embeddings**, re-normalized to unit sphere
- Stored in `prototypes.pkl` (Joblib format, dictionary: `{ username: [192] }`)
- Loaded into FastAPI RAM on startup
- Updated after each successful `/enroll`

### Room Permissions Model
- Rooms numbered 1–6 (hardcoded in `AdminPage` and `DoorPage`)
- Each user can have 0 or more room permissions
- Checked in `AudioController` before granting access

### Session Management (Admin)
- JWT-based authentication (HS256, 10-hour expiry)
- Token stored in `sessionStorage` under key `adminToken` (cleared when tab closes)
- `AdminAuthContext.jsx` manages login/logout state; `api.js` interceptor attaches `Authorization: Bearer <token>` to every request automatically
- Logout calls `sessionStorage.removeItem('adminToken')` and redirects to `/login`
- **Security note:** JWT secret is hardcoded in `application.properties`; production must use the `JWT_SECRET` environment variable instead

---

## Debugging Tips

### FastAPI Model Loading Issues
- Check `best_model.pt` exists at `ai_engine/core/weights/`
- Verify weights are in checkpoint dict (API handles both flat and nested formats)
- If `weights_only=True` fails, model may be from older PyTorch; try loading with `weights_only=False` (less secure)
- Confirm ECAPA_TDNN(C=512, emb=192) architecture matches checkpoint

### Speaker Not Being Recognized
- Verify prototypes file at `shared_data/embeddings_backup/prototypes.pkl` is not empty
- Check `/enroll` endpoint was called successfully (logs show message)
- Test with 3+ distinct audio samples during enrollment (VAD may filter silence)
- Ensure test audio quality (16 kHz mono, sufficient volume)

### Database Connection Errors
- MySQL server running on `localhost:3306`
- Credentials: `root` / `1234` (set in `application.properties`)
- Schema `speaker_db` auto-created on first startup
- Check MySQL error logs if INSERT fails (common issue: nullable constraints)

### React/Spring Boot Communication
- React fetches from `http://localhost:8080/api/*`
- `@CrossOrigin(origins = "*")` on all Spring controllers enables CORS
- Browser console shows network errors if endpoint not reached
- Postman can test Spring endpoints directly without React

---

## File Structure Overview

```
SpeakerAuth/
├── client/                          # React app (Vite)
│   ├── src/
│   │   ├── pages/                   # CorridorPage, DoorPage, LoginPage, AdminPage
│   │   ├── hooks/                   # useAudioRecorder, useAdminAuth
│   │   ├── services/                # api.js (Axios)
│   │   ├── App.jsx                  # Route definitions
│   │   └── main.jsx                 # Entry point
│   ├── package.json
│   └── vite.config.ts
│
├── server/                          # Spring Boot app
│   ├── src/main/java/
│   │   └── com/example/server/
│   │       ├── controllers/         # AudioController, UserController, AccessLogController
│   │       ├── entities/            # User, AccessLog, RoomPermission
│   │       ├── repositories/        # Spring Data JPA interfaces
│   │       └── DemoApplication.java # Entry point, DB initialization
│   ├── src/main/resources/
│   │   └── application.properties
│   ├── pom.xml
│   └── mvnw / mvnw.cmd
│
├── ai_engine/                       # FastAPI app (Python)
│   ├── api.py                       # Main FastAPI server
│   ├── speaker_id_inference.py      # Alternative inference script
│   ├── core/
│   │   ├── model.py                 # ECAPA-TDNN architecture
│   │   ├── pipeline.py              # Audio preprocessing
│   │   ├── weights/
│   │   │   ├── best_model.pt        # ECAPA-TDNN weights (24.8 MB)
│   │   │   └── silero_vad_offline.jit
│   │   └── __pycache__/
│   └── dsp_module/
│       └── speaker_features.pyd     # C++ compiled module for Fbank extraction
│
├── shared_data/                     # Runtime data (created at startup)
│   ├── enrollments/                 # Per-user audio backup
│   └── embeddings_backup/
│       └── prototypes.pkl           # Speaker prototypes (Joblib)
│
└── start_all.bat                    # Windows batch launcher
```

---

## Technology Stack Summary

| Component | Tech | Version/Notes |
|-----------|------|---------------|
| Frontend | React | 18.3.1 |
| Frontend Build | Vite | 5.4.2 |
| Frontend Styling | Tailwind CSS | 3.4.1 |
| Frontend HTTP | Axios | 1.16.1 |
| Backend | Spring Boot | 3.4.12 |
| Backend Framework | Spring Web + JPA | Latest |
| Database | MySQL | 8.0+ (auto-created) |
| ORM | Hibernate | Via Spring Data JPA |
| AI Framework | FastAPI | Latest |
| AI Engine | PyTorch | 2.x (weights_only mode) |
| Speaker Model | ECAPA-TDNN | Custom trained |
| DSP Module | C++ PyBind11 | speaker_features.pyd |
| Voice Activity | Silero VAD | Offline TorchScript model |
| Java Version | OpenJDK | 21 |
| Python Version | CPython | 3.8+ |

---

## Known Limitations & Future Improvements

1. **Admin authentication** — No password hashing; demo-level security only
2. **No persistent admin sessions** — Stored in browser localStorage, lost on refresh in incognito mode
3. **No rate limiting** — Audio uploads and enrollments not throttled
4. **No SSL/TLS** — All communication unencrypted (localhost only)
5. **Hardcoded room numbers** — Rooms 1–6; dynamic room management not implemented
6. **No speaker adaptation** — Prototypes computed once at enrollment; no online learning
7. **No access control auditing webhooks** — Logs only stored in DB, no external alerting
8. **Python/FastAPI testing** — No unit tests for audio processing or model inference
