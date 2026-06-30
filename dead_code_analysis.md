# Dead Code Analysis — SpeakerAuth
## Discovery & Audit Report (Read-Only — No Changes Made)

> **Status:** AUDIT ONLY. Zero files were modified or deleted.
> Awaiting explicit approval before any deletion is executed.

---

## Audit Methodology

1. Read every production-relevant `.py`, `.java`, `.jsx/.js`, and `.cpp` file
2. Traced all import chains from the three entry points:
   - `ai_engine/api.py` (FastAPI entry point)
   - `server/src/main/java/.../DemoApplication.java` (Spring Boot entry point)
   - `client/src/main.jsx` → `App.jsx` (React entry point)
3. For each suspect item: searched the entire workspace for any dynamic reference, callback usage, or config-based invocation before classifying

---

## Summary Table

| # | File / Symbol | Type | Status |
|---|---------------|------|--------|
| 1 | `ai_engine/speaker_id_inference.py` | Entire File | **100% Dead** |
| 2 | `ai_engine/core/model.py` | Entire File | **100% Dead** |
| 3 | `ai_engine/core/pipeline_archived.py` | Entire File | **100% Dead** |
| 4 | `ai_engine/test.py` | Entire File | **100% Dead** |
| 5 | `test.py` (root) | Entire File | **100% Dead** |
| 6 | `diag_1_schema.py` (root) | Entire File | **100% Dead** |
| 7 | `diag_2_code.py` (root) | Entire File | **100% Dead** |
| 8 | `client/src/hooks/useAdminAuth.js` | Entire File | **100% Dead** |
| 9 | `speaker_features.cpp:174` — `get_config()` | C++ Function | **100% Dead** |
| 10 | `AccessLog.java:33,36,39` — 3 zombie fields | Entity Fields | **100% Dead** |
| 11 | `AdminController.java:70` — `POST /reset-password` | HTTP Endpoint | Requires Verification |
| 12 | `ai_engine/core/download_vad_offline.py` | Setup Script | Requires Verification |
| 13 | `ai_engine/dsp_module/setup.py` | Build Script | Requires Verification |

---

## Detailed Findings

---

### Item 1 — `ai_engine/speaker_id_inference.py` · **100% Dead**

**File Path:** `ai_engine/speaker_id_inference.py` (374 lines)

**Reason:**
This is a standalone CLI tool (`python speaker_id_inference.py enroll/identify/list/remove`).
It defines its own complete copy of the model stack:
`SEBlock1d`, `Res2Conv1dReluBn`, `SERes2Block`, `AttentivePool`, `ECAPA_TDNN`, `_audio_to_tensor`,
`extract_embedding`, `enroll`, `identify`, `list_speakers`, `remove_speaker`, `main`.

None of these are imported or called by `api.py`. Confirmed by checking `api.py:14–45` —
**zero imports from this file**.

**Bonus Bug Found (NameError):**
`PROFILE_FILE` is assigned at line 23 but `_load_profiles()` (line 186) and `_save_profiles()`
(line 193, 195) reference `PROFILES_PATH` — **a name that is never defined anywhere in this file**.
Running this CLI tool would immediately crash with `NameError: name 'PROFILES_PATH' is not defined`.

**Evidence:**
- `api.py:14–45` — no import from `speaker_id_inference`
- `api.py:129` — defines its own `ECAPA_TDNN` inline
- `speaker_id_inference.py:186` — `if os.path.exists(PROFILES_PATH):` ← undefined variable

---

### Item 2 — `ai_engine/core/model.py` · **100% Dead**

**File Path:** `ai_engine/core/model.py` (224 lines)

**Reason:**
Defines a second, independently written implementation of the full model stack:
`SEBlock`, `Res2Conv1d`, `SERes2Block`, `AttentivePool`, `ECAPA_TDNN`.

This file is **not imported anywhere** in `api.py` (verified: `api.py:14–45` contains no reference
to `core.model` or `from core import`). `api.py` defines its own identical-but-different inline copy
starting at `api.py:67`.

**Architectural Divergence (risk note):**
The `ECAPA_TDNN.forward()` in `core/model.py:205` calls `F.relu(self.stem(x))` and
`F.relu(self.mfa_conv(...))`. The `api.py` inline version includes `ReLU` inside the
`nn.Sequential` stem (line 133) and does NOT apply `relu` after `mfa_conv` in the same way.
These are live inference differences — the two files do NOT produce identical results.

**Evidence:**
- `api.py:14–45` — no import from `core.model`
- `api.py:129` — `class ECAPA_TDNN(nn.Module):` defined inline
- `api.py:168` — `model = ECAPA_TDNN(C=512, emb=192, scale=8)` — uses inline class

---

### Item 3 — `ai_engine/core/pipeline_archived.py` · **100% Dead**

**File Path:** `ai_engine/core/pipeline_archived.py` (111 lines)

**Reason:**
The filename itself signals its status ("archived"). This is the previous VAD+pipeline
implementation with three functions: `run_vad()`, `_rms_normalize()`, `process_audio_pipeline()`.

**Not called from anywhere.** `api.py` implements its own VAD directly at lines 255–276
(`_apply_vad()`) and its own RMS normalization at lines 295–297, using the `silero_vad` pip
package rather than the manual window-loop approach in this file.

**Evidence:**
- `api.py:14–45` — no import from `core.pipeline_archived` or `core.pipeline`
- git status: `AD ai_engine/core/pipeline.py` — the original `pipeline.py` was added then deleted;
  this archived copy was never wired into `api.py`

---

### Item 4 — `ai_engine/test.py` · **100% Dead**

**File Path:** `ai_engine/test.py` (47 lines)

**Reason:**
A one-shot diagnostic script that reads and prints the contents of `core/pipeline.py`,
`core/download_vad_offline.py`, and `core/model.py`, then searches for calls to
`process_audio_pipeline`, `run_vad`, `_rms_normalize`.

It even references `core/pipeline.py` at line 9 — a file that **no longer exists** (deleted,
see `AD ai_engine/core/pipeline.py` in git status).

Not imported or called by any production code. Pure developer debugging script.

**Evidence:**
- `ai_engine/test.py:9` — `"core/pipeline.py"` — file doesn't exist
- No production code imports this script

---

### Item 5 — `test.py` (root) · **100% Dead**

**File Path:** `test.py` (77 lines)

**Reason:**
An architecture dump generator that walks the repo and concatenates source files into
`project_architecture_dump.txt`. Developer tooling, not part of any service. Not imported
or invoked by Spring Boot, FastAPI, or React.

**Evidence:**
- `test.py:4` — `OUTPUT_FILE = 'project_architecture_dump.txt'` — confirms it is a dump tool
- Not imported by any production code

---

### Item 6 — `diag_1_schema.py` · **100% Dead**

**File Path:** `diag_1_schema.py` (root directory)

**Reason:**
A diagnostic script that connects to MySQL and dumps the full schema to
`diag_output_1_schema.txt`. One-shot developer tool. Not invoked by any service.

**Evidence:**
- `diag_1_schema.py:19` — `OUT_FILE = Path("diag_output_1_schema.txt")`
- Not imported or called by any production code

---

### Item 7 — `diag_2_code.py` · **100% Dead**

**File Path:** `diag_2_code.py` (root directory)

**Reason:**
A diagnostic script that dumps all Java + Python + React source code into
`diag_output_2_code.txt`. One-shot developer tool. Not invoked by any service.

**Evidence:**
- `diag_2_code.py:18` — `OUT_FILE = Path("diag_output_2_code.txt")`
- Not imported or called by any production code

---

### Item 8 — `client/src/hooks/useAdminAuth.js` · **100% Dead**

**File Path:** `client/src/hooks/useAdminAuth.js` (29 lines)

**Reason:**
This is the **old, insecure auth hook** that validates the admin password by comparing it
client-side against a hardcoded constant:

```javascript
// useAdminAuth.js:8
const ADMIN_PASSWORD = 'admin123'
// useAdminAuth.js:14
if (password === ADMIN_PASSWORD) { ... }
```

This hook has been **fully superseded** by `AdminAuthContext.jsx` which sends credentials
to Spring Boot and validates against BCrypt hash in the database.

Global search for `useAdminAuth` (without the `Context` suffix) confirms: **zero imports in
any component**. `App.jsx`, `AdminPage.jsx`, and `LoginPage.jsx` all import exclusively from
`AdminAuthContext.jsx`.

**Security Note:** This file contains a hardcoded password (`admin123`). Even though unused,
it is a credential in the codebase and should be deleted.

**Evidence:**
- `client/src/App.jsx:2` — imports only from `'./context/AdminAuthContext.jsx'`
- `client/src/pages/AdminPage.jsx:21` — `import { useAdminAuthContext }`
- `client/src/pages/LoginPage.jsx:3` — `import { useAdminAuthContext }`
- Zero files import `useAdminAuth` (the default export from this file)

---

### Item 9 — `speaker_features.cpp:174` — `get_config()` · **100% Dead**

**File Path:** `ai_engine/dsp_module/speaker_features.cpp`, line 174–197

**Reason:**
The C++ module exports two functions via `PYBIND11_MODULE`:
1. `extract` (line 196) — **actively used**: called as `_spk_feat.extract(...)` in `api.py:311`
2. `get_config` (line 197) — **never called** from any Python file

Global search across all `.py` files for `get_config` and `spk_feat.get_config` and
`speaker_features.get_config` returns **zero matches** in any non-C++ file.

The function exists as a debugging/verification utility and is safe to remove from the
PYBIND11_MODULE registration without breaking anything.

**Evidence:**
- `speaker_features.cpp:197` — `m.def("get_config", &get_config, ...)`
- `api.py:311` — `_spk_feat.extract(...)` — only `extract` is used
- Zero `.py` files call `get_config`

---

### Item 10 — `AccessLog.java:33,36,39` — Three Zombie Fields · **100% Dead**

**File Path:** `server/src/main/java/com/example/server/entities/AccessLog.java`

**Three fields that are mapped to DB columns but never written:**

| Field | Line | DB Column | Has Setter? | Ever Written? |
|-------|------|-----------|-------------|---------------|
| `ipAddress` | 33 | `ip_address` | ❌ No | Never |
| `processingTimeMs` | 36 | `processing_time_ms` | ❌ No | Never |
| `rejectionReason` | 39 | `rejection_reason` | ❌ No | Never |

**Reason:**
The only constructor used in production is `AccessLog(filename, identifiedSpeaker, confidence,
accessGranted, roomNumber)` (line 45). It does not populate these three fields.
No setter methods are defined for them, and no call site ever sets them.
MySQL always stores NULL for all three columns.

Getters exist (lines 63–65) and Jackson serializes these fields to JSON, meaning React
currently receives `"ipAddress": null, "processingTimeMs": null, "rejectionReason": null`
in every log entry — but `AdminPage.jsx` never reads or displays these keys.

**Evidence:**
- `AccessLog.java:45–53` — constructor: 5 params, no assignment to ipAddress/processingTimeMs/rejectionReason
- `AccessLog.java` — no `setIpAddress()`, no `setProcessingTimeMs()`, no `setRejectionReason()`
- `AudioService.java:109–110` — only 5-arg constructor used: `new AccessLog(filename, detectedName, confidence, decision.approved(), roomId)`
- `AdminPage.jsx:227–238` — log table renders only: timestamp, roomNumber, identifiedSpeaker, confidence, accessGranted

---

### Item 11 — `AdminController.java:70` — `POST /api/admin/reset-password` · ⚠️ Requires Verification

**File Path:** `server/src/main/java/com/example/server/controllers/AdminController.java`, lines 70–80
**Backed by:** `AdminService.java:100–106` — `resetPassword()`

**Reason for Flag:**
This is a fully functional HTTP endpoint (`POST /api/admin/reset-password`) with corresponding
Service logic. However, **no React code ever calls it**:

- `client/src/services/api.js` — no `resetPassword` export
- `client/src/pages/AdminPage.jsx` — no reset-password UI element
- Global search for `reset.password|resetPassword` in `client/src` — **zero matches**

**Why "Requires Verification" and not "100% Dead":**
An HTTP endpoint can be called by external tools (Postman, curl, integration tests) even without
a UI. This may be an intentional administrative back-channel. It requires a human decision:
**is this an intentional utility endpoint, or should it be removed along with an AdminPage UI?**

**Evidence:**
- `AdminController.java:70` — `@PostMapping("/reset-password")`
- `client/src/services/api.js` — no function referencing `/reset-password`
- `AdminPage.jsx` — no reset password form or button

---

### Item 12 — `ai_engine/core/download_vad_offline.py` · ⚠️ Requires Verification

**File Path:** `ai_engine/core/download_vad_offline.py` (16 lines)

**Reason for Flag:**
A one-time setup script that downloads the Silero VAD model from the internet and saves it as
`silero_vad_offline.jit`. The output file already exists in `core/weights/`, so this script
has already fulfilled its purpose and will never be called again in normal operation.

**Why "Requires Verification" and not "100% Dead":**
If `silero_vad_offline.jit` is ever deleted or corrupted, this script is the only documented
way to regenerate it. It functions as living documentation / recovery procedure.
Human decision: archive it in a `scripts/` directory, or delete it because the `.jit` is in git.

**Evidence:**
- `api.py:189` — `_vad_path = os.path.join(..., "silero_vad_offline.jit")` — file must exist
- `download_vad_offline.py:15` — `torch.jit.save(model, "silero_vad_offline.jit")`
- Not called from any production code

---

### Item 13 — `ai_engine/dsp_module/setup.py` · ⚠️ Requires Verification

**File Path:** `ai_engine/dsp_module/setup.py` (24 lines)

**Reason for Flag:**
A `setuptools` build script for compiling `speaker_features.cpp` into `speaker_features.pyd`.
The compiled `.pyd` file already exists and is used in production (`api.py:40`).
`setup.py` itself is never run during normal server operation.

**Why "Requires Verification" and not "100% Dead":**
If `speaker_features.cpp` is ever modified or the Python version changes, this script is
the build tool. It is infrastructure code, not application code.
Human decision: keep it in place (standard practice for PyBind11 projects) or move to `scripts/`.

**Evidence:**
- `setup.py:9` — `"speaker_features"` — builds the `.pyd`
- `api.py:40` — `import speaker_features as _spk_feat` — uses the compiled `.pyd`
- `setup.py` itself is not imported or called at runtime

---

## Files Explicitly NOT Flagged (False Positive Prevention)

| File | Reason NOT Flagged |
|------|--------------------|
| `api.py` — `@app.on_event("startup")` | FastAPI lifecycle hook — called by uvicorn |
| `api.py` — `@app.post("/enroll")`, `@app.post("/predict")` | FastAPI route decorators — invoked by HTTP |
| All `@Service`, `@RestController`, `@Component` Java classes | Spring DI — instantiated by Spring context, not `new` |
| All `findBy...()` methods in Repository interfaces | Spring Data JPA — generated at runtime from method name |
| `JwtUtil.extractExpiration()` | Called by `isTokenExpired()` (line 82) → `validateToken()` → `JwtFilter` |
| `AdminService.resetPassword()` | Called by `AdminController` (live endpoint, see Item 11) |
| `AccessLog` getters (`getIpAddress`, etc.) | Jackson serializes them; flagged at field level (Item 10), not getter level |
| `useAudioRecorder.js` | Imported by `CorridorPage.jsx:16` and `DoorPage.jsx` |
| `AdminAuthContext.jsx` — `ProtectedRoute` | Used in `App.jsx:16` |

---

## Risk Priority for Deletion

When you approve deletions, suggested order (safest first):

1. **Items 5, 6, 7** — root-level diagnostic scripts (zero runtime impact)
2. **Item 8** — `useAdminAuth.js` (security risk — hardcoded password)
3. **Items 3, 4** — archived/test Python files
4. **Item 9** — `get_config` C++ export (one-line removal in `speaker_features.cpp`)
5. **Item 10** — three zombie fields in `AccessLog.java` (requires DB migration to drop columns)
6. **Items 1, 2** — large Python files (`speaker_id_inference.py`, `core/model.py`)
7. **Items 11, 12, 13** — human decision required first
