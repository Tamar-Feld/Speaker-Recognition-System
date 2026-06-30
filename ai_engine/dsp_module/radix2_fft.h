#pragma once
#include <complex>
#include <vector>
#include <cmath>
#include <cassert>
#include <stdexcept>

#ifndef M_PI
  static constexpr double M_PI = 3.14159265358979323846264338327950288;
#endif

/* ─── module-level twiddle table ──────────────────────────────────── */
static std::vector<std::complex<double>> TWIDDLE_TABLE; //הטבלה ששומר את ערכי החישוב של cos וsin כדי שלא יחשבו כל פעם מחדש

/*
 * fft_build_twiddles(N)
 */
inline void fft_build_twiddles(int N)
{
    if ((N & (N - 1)) != 0) //בא נבדוק אם הN שייך לחזקות 2 אם לא זה שגיאה כי האלגוריתם דורש שאפשר לחלק את המערך ל2 שוב ושוב עד 1.
        throw std::runtime_error("fft_build_twiddles: N must be power of 2");
    TWIDDLE_TABLE.resize(N / 2); //הגדרת גודל הטבלה החצי מN בגלל ששרשי היחידה הם סימטריים ומספיק לחשב רק חצי ראשון וכל מה שיותר זה אותו ערך
    for (int k = 0; k < N / 2; ++k) { //
        const double ang = -2.0 * M_PI * k / N;
        TWIDDLE_TABLE[k] = { std::cos(ang), std::sin(ang) };//מכניס לטבלה את ערך המספר המרוכב עבור התדר הזה
    }
}

/*
 * fft_inplace(x)
 * --------------
 * In-place DFT  (forward, negative-exponent convention).
 * x.size() must equal the N passed to fft_build_twiddles().
 *
 * Twiddle lookup:  w = TWIDDLE_TABLE[j * step]
 *   where step = N / len.  Max index = N/2 - 1  (always in-bounds).
 */
static void fft_inplace(std::vector<std::complex<double>>& x)
{
    const int N = static_cast<int>(x.size());
    assert(!TWIDDLE_TABLE.empty()                          // built?
        && static_cast<int>(TWIDDLE_TABLE.size()) == N/2); // right size?

    /* bit-reversal */ // מבצעים היפוךביטים כי כך האלגוריתם דורש את הסדר ההפוך ביטית ככה בסוף התוצאה מסודרת נכון בלי סידור נוסף
    for (int i = 1, j = 0; i < N; ++i) {
        int bit = N >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(x[i], x[j]);
    }

    /* butterfly stages — twiddle lookup, zero accumulated drift */
    for (int len = 2; len <= N; len <<= 1) {
        const int step = N / len;                  // index stride into table
        for (int i = 0; i < N; i += len) {
            for (int j = 0; j < len / 2; ++j) {
                const std::complex<double>  w = TWIDDLE_TABLE[j * step];
                const std::complex<double>  u = x[i + j];
                const std::complex<double>  v = x[i + j + len/2] * w;
                x[i + j]           = u + v;
                x[i + j + len/2]   = u - v;
            }
        }
    }
}
