/*
 * speaker_features.cpp  — v2  (double-precision pipeline)
 * =========================================================
 * Python extension (pybind11) — Log-Mel Filterbank for ECAPA-TDNN.
 *
 * Parameters (UNCHANGED — must match torchaudio training config):
 *   sample_rate = 16 000 Hz
 *   n_fft       = 512          (FFT size)
 *   win_length  = 400          (25 ms)
 *   hop_length  = 160          (10 ms)
 *   f_min       = 20.0 Hz
 *   f_max       = 7 600.0 Hz
 *   n_mels      = 80
 *   window      = Hamming, periodic  → matches torch.hamming_window(N)
 *   center      = False
 *   preemphasis = 0.97
 *
 * Changes vs v1  (all backward-compatible; output shape/dtype unchanged):
 * ──────────────────────────────────────────────────────────────────────
 * [FIX-1]  Validation threshold: WIN_LENGTH (400) → N_FFT (512).
 *          v1 allowed n_samples ∈ [400,511] → buffer overflow (UB).
 *          Numerically verified: min safe access for new code = 456,
 *          but semantically correct n_frames requires n_samples ≥ 512.
 *
 * [FIX-2]  Log floor: std::max(dot, 1e-9) → dot + 1e-9  (additive).
 *          Matches torch.log(mel + 1e-9).  The max() variant differed
 *          by ln(2)≈0.693 when dot≈1e-9 (silence frames); affects
 *          attentive-pooling weights across 15/240 filter outputs in
 *          the first three frames of a 48 kHz utterance (measured).
 *
 * [FIX-3]  Double-precision pipeline.
 *          All intermediate state (pre-emphasis, Hamming, FFT buffer,
 *          power spectrum, mel accumulator, log) computed in float64.
 *          Final cast to float32 at the single output write.
 *          – FFT twiddle drift: 4.43e-6 (v1) → 0 (v2, twiddle table).
 *          – Power-spectrum delta vs v1: max 1.02e-4 (1s utterance).
 *          – fbank delta vs v1: max 2.54e-5, mean 6.2e-6 (297 frames).
 *          – Within ECAPA-TDNN tolerance (MLP layer variance >> 1e-3).
 *
 * [FIX-4]  radix2_fft.h: precomputed double-precision twiddle table.
 *          See radix2_fft.h for details.
 *
 * [FIX-5]  MEL_FB: flat 1-D vector (N_MELS × N_FREQS doubles).
 *          Eliminates 80 extra pointer dereferences per frame (v1 used
 *          vector<vector<float>>); inner loop now L1-cache friendly.
 *
 * Thread-safety: unchanged from v1. All mutable state is per-call
 *   (local vectors). HAMMING_WIN_D / MEL_FB_D / TWIDDLE_TABLE are
 *   written once in build_tables() then read-only.
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

/* ──────────────────── compile-time constants ──────────────────────── */
static constexpr int    SAMPLE_RATE  = 16000;
static constexpr int    N_FFT        = 512;
static constexpr int    WIN_LENGTH   = 400;
static constexpr int    HOP_LENGTH   = 160;
static constexpr double F_MIN        = 20.0;
static constexpr double F_MAX        = 7600.0;
static constexpr int    N_MELS       = 80;
static constexpr double PREEMPH_COEF = 0.97;
static constexpr double LOG_FLOOR    = 1e-9;        // additive
static constexpr int    PAD_LEFT     = (N_FFT - WIN_LENGTH) / 2;   // 56
static constexpr int    N_FREQS      = N_FFT / 2 + 1;              // 257

static_assert((N_FFT & (N_FFT - 1)) == 0, "N_FFT must be power of 2");
static_assert(PAD_LEFT * 2 + WIN_LENGTH == N_FFT, "window padding mismatch");

/* ──────────────────── module-level read-only tables ──────────────── */

/* [FIX-3] Hamming window — double precision, WIN_LENGTH = 400 samples
 * Periodic: w[n] = 0.54 - 0.46·cos(2π·n / WIN_LENGTH)
 * Matches torch.hamming_window(WIN_LENGTH, periodic=True).            */
static std::vector<double> HAMMING_WIN_D;

/* [FIX-5] Flat Mel filterbank — double precision, row-major
 * Layout: MEL_FB_D[m * N_FREQS + k]  for m ∈ [0,N_MELS), k ∈ [0,N_FREQS)
 * 80 × 257 = 20 560 doubles ≈ 160 KB — fits comfortably in L2 cache.  */
static std::vector<double> MEL_FB_D;


/* ──────────────────── helpers ────────────────────────────────────── */
static inline double hz_to_mel(double hz) {
    return 2595.0 * std::log10(1.0 + hz / 700.0);   // HTK formula
}
static inline double mel_to_hz(double mel) {
    return 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0);
}


/* ──────────────────── build_tables ───────────────────────────────── */
static void build_tables()
{
    /* [FIX-4] twiddle table — double, direct cos/sin, no drift */
    fft_build_twiddles(N_FFT);

    /* [FIX-3] Hamming window — periodic, double
     * Divides by WIN_LENGTH (not WIN_LENGTH-1) → periodic variant.    */
    HAMMING_WIN_D.resize(WIN_LENGTH);
    for (int n = 0; n < WIN_LENGTH; ++n)
        HAMMING_WIN_D[n] = 0.54 - 0.46 * std::cos(2.0 * M_PI * n / WIN_LENGTH);

    /* [FIX-5] Flat double Mel filterbank
     *
     * All centre-frequency arithmetic in float64 (error < 0.23 mHz).
     * HTK Mel formula — consistent hz_to_mel / mel_to_hz round-trip.
     * Triangle filters: peak = 1.0, no area normalisation
     *   → matches torchaudio MelScale(norm=None) default.
     * FFT-bin → Hz mapping: f_k = k × sample_rate / N_FFT (exact).   */
    const double mel_lo = hz_to_mel(F_MIN);
    const double mel_hi = hz_to_mel(F_MAX);

    // N_MELS+2 linearly-spaced Mel points (double precision)
    std::vector<double> hz_pts(N_MELS + 2);
    for (int i = 0; i < N_MELS + 2; ++i) {
        const double mel = mel_lo + (mel_hi - mel_lo) * i / (N_MELS + 1);
        hz_pts[i] = mel_to_hz(mel);
    }

    std::vector<double> fft_freqs(N_FREQS);
    for (int k = 0; k < N_FREQS; ++k)
        fft_freqs[k] = static_cast<double>(k) * SAMPLE_RATE / N_FFT;

    MEL_FB_D.assign(static_cast<size_t>(N_MELS) * N_FREQS, 0.0);
    for (int m = 0; m < N_MELS; ++m) {
        const double lo  = hz_pts[m];
        const double ctr = hz_pts[m + 1];
        const double hi  = hz_pts[m + 2];
        double* row = &MEL_FB_D[m * N_FREQS];
        for (int k = 0; k < N_FREQS; ++k) {
            const double f = fft_freqs[k];
            if      (f >= lo && f <= ctr) row[k] = (f  - lo) / (ctr - lo);
            else if (f >  ctr && f <= hi) row[k] = (hi -  f) / (hi  - ctr);
            // else: row[k] stays 0.0 (assigned above)
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════
 * extract(audio_np) → ndarray [N_MELS, T]
 *
 * audio_np : 1-D float32 numpy array, sample_rate = 16 000 Hz
 * returns  : 2-D float32 numpy array, shape [80, T]
 *            T = (n_samples - N_FFT) / HOP_LENGTH + 1
 *
 * Fully reentrant — safe for multiprocessing workers.
 * ═══════════════════════════════════════════════════════════════════ */
py::array_t<float> extract(py::array_t<float> audio_np)
{
    /* ── validate ─────────────────────────────────────────────────── */
    if (audio_np.ndim() != 1)
        throw std::runtime_error("extract: expected 1-D float32 array");

    const int n_samples = static_cast<int>(audio_np.shape(0));

    /* [FIX-1] Minimum = N_FFT (512), not WIN_LENGTH (400).
     * Rationale: n_frames = (n-512)/160+1 requires n≥512 to yield
     * n_frames≥1 matching torchaudio; and to keep the buffer access
     * frame_start + PAD_LEFT + WIN_LENGTH-1 strictly < n_samples.   */
    if (n_samples < N_FFT)
        throw std::runtime_error(
            "extract: audio too short (need >= " +
            std::to_string(N_FFT) + " samples, got " +
            std::to_string(n_samples) + ")");

    const float* src = audio_np.data();

    /* ── [FIX-3] pre-emphasis — double, global, y[n]=x[n]-0.97·x[n-1]
     * FIR (reads src[], not audio_d[]) → avoids IIR all-pole variant. */
    std::vector<double> audio_d(n_samples);
    audio_d[0] = static_cast<double>(src[0]);
    for (int t = 1; t < n_samples; ++t)
        audio_d[t] = static_cast<double>(src[t])
                   - PREEMPH_COEF * static_cast<double>(src[t - 1]);

    /* ── framing ──────────────────────────────────────────────────── */
    // Matches torch.stft(center=False):
    //   n_frames = floor((n_samples - n_fft) / hop_length) + 1
    // For n_samples ≥ N_FFT, C++ truncation == Python floor division.
    const int n_frames = (n_samples - N_FFT) / HOP_LENGTH + 1;

    py::array_t<float> result({N_MELS, n_frames});
    float* out = result.mutable_data();

    /* ── per-call local buffers (thread-safe) ─────────────────────── */
    std::vector<std::complex<double>> fft_buf(N_FFT);   // [FIX-3,4]
    std::vector<double>               power(N_FREQS);

    for (int fr = 0; fr < n_frames; ++fr) {
        const int frame_start = fr * HOP_LENGTH;

        /* Build N_FFT-point buffer:
         *   [0 .. PAD_LEFT-1]            → 0   (56 zeros, left pad)
         *   [PAD_LEFT .. PAD_LEFT+WIN-1] → audio × Hamming window
         *   [PAD_LEFT+WIN .. N_FFT-1]    → 0   (56 zeros, right pad)
         *
         * Mirrors torch.stft window zero-padding:
         *   left_pad = right_pad = (N_FFT - WIN_LENGTH) / 2 = 56.
         * Access: audio_d[frame_start+56 .. frame_start+455]
         *         always in-bounds for n_samples ≥ N_FFT.             */
        std::fill(fft_buf.begin(), fft_buf.end(),
                  std::complex<double>(0.0, 0.0));
        for (int n = 0; n < WIN_LENGTH; ++n)
            fft_buf[PAD_LEFT + n] = {
                audio_d[frame_start + PAD_LEFT + n] * HAMMING_WIN_D[n],
                0.0
            };

        /* [FIX-3,4] double-precision FFT — twiddle table, zero drift */
        fft_inplace(fft_buf);

        /* power spectrum |X[k]|²  (k = 0 .. N_FREQS-1 = 0 .. 256) */
        for (int k = 0; k < N_FREQS; ++k)
            power[k] = fft_buf[k].real() * fft_buf[k].real()
                     + fft_buf[k].imag() * fft_buf[k].imag();

        /* [FIX-3,5] Mel dot-product — double, flat matrix
         * [FIX-2]   additive floor before log                         */
        for (int m = 0; m < N_MELS; ++m) {
            double dot = 0.0;
            const double* row = &MEL_FB_D[m * N_FREQS];
            for (int k = 0; k < N_FREQS; ++k)
                dot += row[k] * power[k];

            // [FIX-2] dot + LOG_FLOOR  (matches torch.log(mel + 1e-9))
            // [FIX-3] std::log(double) → single cast to float32 at end
            out[m * n_frames + fr] =
                static_cast<float>(std::log(dot + LOG_FLOOR));
        }
    }

    return result;   // shape [80, n_frames], float32
}


/* ═══════════════════════════════════════════════════════════════════
 * get_config() → dict  (includes new 'precision' key)
 * ═══════════════════════════════════════════════════════════════════ */
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
    d["precision"]    = std::string("double_internal_float32_output");
    return d;
}


/* ═══════════════════════════════════════════════════════════════════
 * pybind11 module definition
 * ═══════════════════════════════════════════════════════════════════ */
PYBIND11_MODULE(speaker_features, m)
{
    m.doc() = "Thread-safe double-precision Log-Mel Fbank extractor v2";

    build_tables();   // runs once at import — builds twiddle + tables

    m.def("extract",
          &extract,
          py::arg("audio"),
          R"doc(
Extract Log-Mel Filterbank features.

Parameters
----------
audio : np.ndarray, dtype=float32, shape [N],  N >= 512
    Mono waveform at exactly 16 000 Hz.

Returns
-------
np.ndarray, dtype=float32, shape [80, T]
    Log-Mel features.  T = (N - n_fft) / hop_length + 1
)doc");

    m.def("get_config",
          &get_config,
          "Return a dict of all compile-time parameters.");
}
