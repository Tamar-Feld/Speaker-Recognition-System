/*
 * speaker_features.cpp
 * ====================
 * Python extension (pybind11) that computes Log-Mel Filterbank features.
 *
 * Parameters — MUST be identical to the torchaudio config in training:
 *   sample_rate = 16 000 Hz
 *   n_fft       = 512       (FFT size)
 *   win_length  = 400       (25 ms)
 *   hop_length  = 160       (10 ms)
 *   f_min       = 20.0 Hz
 *   f_max       = 7 600.0 Hz
 *   n_mels      = 80
 *   window      = Hamming (periodic variant, matches torch.hamming_window)
 *   center      = False  → frames = (n_samples - win_length) / hop_length + 1
 *   preemphasis = 0.97
 *   log_floor   = 1e-9
 *
 * Thread-safety guarantee:
 *   All per-call state lives on the stack / in local vectors.
 *   The only globals are read-only tables built at module init
 *   (hamming window, mel filter matrix).  No locks needed.
 *
 * Build:
 *   See CMakeLists.txt — requires pybind11 ≥ 2.10
 *
 * Usage:
 *   import speaker_features
 *   fbank = speaker_features.extract(audio_float32_1d)   # → ndarray [80, T]
 *   cfg   = speaker_features.get_config()               # → dict
 */

#include <pybind11/pybind11.h>
#include <pybind11/numpy.h>
#include <cmath>
#include <complex>
#include <vector>
#include <stdexcept>
#include <string>
#include <algorithm>
#include "radix2_fft.h"

namespace py = pybind11;

/* ─────────────────────── compile-time constants ─────────────────────── */
static constexpr int   SAMPLE_RATE  = 16000;
static constexpr int   N_FFT        = 512;
static constexpr int   WIN_LENGTH   = 400;
static constexpr int   HOP_LENGTH   = 160;
static constexpr float F_MIN        = 20.0f;
static constexpr float F_MAX        = 7600.0f;
static constexpr int   N_MELS       = 80;
static constexpr float PREEMPH_COEF = 0.97f;
static constexpr float LOG_FLOOR    = 1e-9f;
static constexpr float PI_F         = 3.14159265358979323846f;
/* ─────────────────────────────────────────────────────────────────────── */


/* ═══════════════════════════════════════════════════════════════════════
 *  Module-level read-only tables (initialised once at import time)
 * ═══════════════════════════════════════════════════════════════════════ */

/* Hamming window — PERIODIC variant, matches torch.hamming_window(N)
 * w[n] = 0.54 - 0.46 * cos(2π * n / N)   (divides by N, not N-1)      */
static std::vector<float> HAMMING_WIN;

/* Mel filterbank matrix: shape [N_MELS, N_FFT/2+1]
 * Each row contains the triangular filter weights for one Mel band.     */
static std::vector<std::vector<float>> MEL_FB;


/* ─── helpers ─────────────────────────────────────────────────────────── */
static inline float hz_to_mel(float hz) {
    return 2595.0f * std::log10(1.0f + hz / 700.0f);
}
static inline float mel_to_hz(float mel) {
    return 700.0f * (std::pow(10.0f, mel / 2595.0f) - 1.0f);
}


/* ─── build_tables ────────────────────────────────────────────────────── */
static void build_tables()
{
    /* Hamming window */
    HAMMING_WIN.resize(WIN_LENGTH);
    for (int n = 0; n < WIN_LENGTH; ++n)
        HAMMING_WIN[n] = 0.54f - 0.46f * std::cos(2.0f * PI_F * n / WIN_LENGTH);

    /* Mel filterbank */
    const int n_freqs = N_FFT / 2 + 1;   // 257
    const float mel_lo = hz_to_mel(F_MIN);
    const float mel_hi = hz_to_mel(F_MAX);

    // N_MELS+2 linearly-spaced Mel points → convert to Hz → map to FFT bins
    std::vector<float> mel_pts(N_MELS + 2);
    for (int i = 0; i < N_MELS + 2; ++i)
        mel_pts[i] = mel_lo + (mel_hi - mel_lo) * i / (N_MELS + 1);

    std::vector<float> hz_pts(N_MELS + 2);
    for (int i = 0; i < N_MELS + 2; ++i)
        hz_pts[i] = mel_to_hz(mel_pts[i]);

    // FFT frequency for each bin k: f_k = k * sample_rate / N_FFT
    std::vector<float> fft_freqs(n_freqs);
    for (int k = 0; k < n_freqs; ++k)
        fft_freqs[k] = k * (float)SAMPLE_RATE / N_FFT;

    MEL_FB.assign(N_MELS, std::vector<float>(n_freqs, 0.0f));
    for (int m = 0; m < N_MELS; ++m) {
        const float lo  = hz_pts[m];
        const float ctr = hz_pts[m + 1];
        const float hi  = hz_pts[m + 2];
        for (int k = 0; k < n_freqs; ++k) {
            const float f = fft_freqs[k];
            if (f >= lo && f <= ctr)
                MEL_FB[m][k] = (f - lo) / (ctr - lo);
            else if (f > ctr && f <= hi)
                MEL_FB[m][k] = (hi - f) / (hi - ctr);
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════
 *  extract(audio_np) → ndarray [N_MELS, T]
 *
 *  audio_np : 1-D float32 numpy array, sample_rate = 16 000 Hz
 *  returns  : 2-D float32 numpy array, shape [80, T]
 *
 *  All local state → fully reentrant → safe for multiprocessing workers
 * ═══════════════════════════════════════════════════════════════════════ */
py::array_t<float> extract(py::array_t<float> audio_np)
{
    /* ── validate input ──────────────────────────────────────────────── */
    if (audio_np.ndim() != 1)
        throw std::runtime_error("extract: expected 1-D float32 array");

    const int n_samples = (int)audio_np.shape(0);
    if (n_samples < WIN_LENGTH)
        throw std::runtime_error(
            "extract: audio too short (need >= " +
            std::to_string(WIN_LENGTH) + " samples, got " +
            std::to_string(n_samples) + ")");

    const float* src = audio_np.data();

    /* ── pre-emphasis: y[t] = x[t] - 0.97*x[t-1] ───────────────────── */
    std::vector<float> audio(n_samples);
    audio[0] = src[0];
    for (int t = 1; t < n_samples; ++t)
        audio[t] = src[t] - PREEMPH_COEF * src[t - 1];

    /* ── framing (center=False) ─────────────────────────────────────── */
    // torch.stft uses n_fft (not win_length) for the stride boundary:
    //   n_frames = (n_samples - N_FFT) / HOP_LENGTH + 1
    // Example: (48000 - 512) / 160 + 1 = 297  ← matches torchaudio exactly.
    // Using WIN_LENGTH here gives 298 — one extra frame → shape mismatch.
    const int n_frames = (n_samples - N_FFT) / HOP_LENGTH + 1;
    const int n_freqs  = N_FFT / 2 + 1;   // 257

    // output buffer: [N_MELS, n_frames]
    py::array_t<float> result({N_MELS, n_frames});
    float* out = result.mutable_data();
    // zero-initialise (important for frames near the end)
    std::fill(out, out + N_MELS * n_frames, 0.0f);

    // reusable FFT buffer (local → thread-safe)
    std::vector<std::complex<float>> fft_buf(N_FFT);
    std::vector<float> power(n_freqs);

    // Matches torch.stft behaviour: each frame spans N_FFT samples;
    // the WIN_LENGTH window is centered inside the N_FFT buffer.
    // pad_left = (N_FFT - WIN_LENGTH) / 2 = 56 for N_FFT=512, WIN=400.
    constexpr int PAD_LEFT = (N_FFT - WIN_LENGTH) / 2;   // 56

    for (int fr = 0; fr < n_frames; ++fr) {
        const int frame_start = fr * HOP_LENGTH;   // start of N_FFT-sample frame

        /* Build N_FFT-sample buffer:
         *   positions [0         .. PAD_LEFT-1]         → zero
         *   positions [PAD_LEFT  .. PAD_LEFT+WIN-1]     → audio × Hamming window
         *   positions [PAD_LEFT+WIN .. N_FFT-1]         → zero               */
        for (int n = 0; n < N_FFT; ++n) {
            const int win_n = n - PAD_LEFT;   // index into win array
            if (win_n >= 0 && win_n < WIN_LENGTH)
                fft_buf[n] = std::complex<float>(
                    audio[frame_start + n] * HAMMING_WIN[win_n], 0.0f);
            else
                fft_buf[n] = std::complex<float>(0.0f, 0.0f);
        }

        fft_inplace(fft_buf);   // in-place Radix-2 FFT

        /* power spectrum: |X[k]|^2 */
        for (int k = 0; k < n_freqs; ++k)
            power[k] = fft_buf[k].real() * fft_buf[k].real()
                     + fft_buf[k].imag() * fft_buf[k].imag();

        /* apply mel filterbank + log */
        for (int m = 0; m < N_MELS; ++m) {
            float dot = 0.0f;
            const auto& row = MEL_FB[m];
            for (int k = 0; k < n_freqs; ++k)
                dot += row[k] * power[k];
            out[m * n_frames + fr] = std::log(std::max(dot, LOG_FLOOR));
        }
    }

    return result;   // shape [80, n_frames]
}


/* ═══════════════════════════════════════════════════════════════════════
 *  get_config() → dict  — query all parameters at runtime
 * ═══════════════════════════════════════════════════════════════════════ */
py::dict get_config()
{
    py::dict d;
    d["sample_rate"]  = SAMPLE_RATE;
    d["n_fft"]        = N_FFT;
    d["win_length"]   = WIN_LENGTH;
    d["hop_length"]   = HOP_LENGTH;
    d["f_min"]        = F_MIN;
    d["f_max"]        = F_MAX;
    d["n_mels"]       = N_MELS;
    d["preemph_coef"] = PREEMPH_COEF;
    d["log_floor"]    = LOG_FLOOR;
    d["center"]       = false;
    d["window"]       = std::string("hamming_periodic");
    return d;
}


/* ═══════════════════════════════════════════════════════════════════════
 *  pybind11 module definition
 * ═══════════════════════════════════════════════════════════════════════ */
PYBIND11_MODULE(speaker_features, m)
{
    m.doc() = "Thread-safe Log-Mel Fbank extractor for speaker verification";

    // Build read-only tables once at import time
    build_tables();

    m.def("extract",
          &extract,
          py::arg("audio"),
          R"doc(
Extract Log-Mel Filterbank features.

Parameters
----------
audio : np.ndarray, dtype=float32, shape [N]
    Mono waveform at exactly 16 000 Hz.

Returns
-------
np.ndarray, dtype=float32, shape [80, T]
    Log-Mel features.  T = (N - win_length) / hop_length + 1
)doc");

    m.def("get_config",
          &get_config,
          "Return a dict of all compilation-time parameters.");
}
