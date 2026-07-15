#pragma once
#include <complex>
#include <vector>
#include <cmath>
#include <cassert>
#include <stdexcept>

#ifndef M_PI
  static constexpr double M_PI = 3.14159265358979323846264338327950288;
#endif

static std::vector<std::complex<double>> TWIDDLE_TABLE; // טבלה גלובלית לפקטורי הסיבוב (cos ו-sin), מחושבת פעם אחת כדי לא לחשב מחדש בכל קריאה

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

/**
 * מבצעת את פעולת ה-"פרפר" (Butterfly Operation) הקלאסית.
 * מחשבת את השילוב של שני איברים מרוכבים בהתבסס על פקטור הסיבוב.
 */
inline void apply_butterfly_operation(
    std::complex<double>& left_element,
    std::complex<double>& right_element,
    const std::complex<double>& twiddle_factor)
{
    const std::complex<double> u = left_element;
    const std::complex<double> v = right_element * twiddle_factor;

    left_element  = u + v;
    right_element = u - v;
}

/**
 * מבצעת איטרציה בודדת של שלב ה-FFT עבור בלוק נתון.
 */
inline void perform_fft_stage(
    std::vector<std::complex<double>>& x,
    int block_size,
    int step)
{
    const int N = static_cast<int>(x.size());
    const int half_block = block_size / 2;

    for (int i = 0; i < N; i += block_size) {
        for (int j = 0; j < half_block; ++j) {
            const std::complex<double>& w = TWIDDLE_TABLE[j * step];

            apply_butterfly_operation(
                x[i + j],
                x[i + j + half_block], w );
        }// פקטור סיבוב עבור כל תדר
    }
}

/**
 * הפונקציה הראשית המנהלת את שלבי ה-FFT.
 */
static void fft_inplace(std::vector<std::complex<double>>& x)
{
    const int N = static_cast<int>(x.size());

    // שלב 1: סידור מחדש של המערך לפי היפוך ביטים (Bit-Reversal)
    perform_bit_reversal(x);

    // שלב 2: ביצוע שלבי ה-FFT באיטרציות (Radix-2)
    for (int block_size = 2; block_size <= N; block_size <<= 1) {
        const int step = N / block_size;
        perform_fft_stage(x, block_size, step);
    }
}