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

static constexpr int    SAMPLE_RATE  = 16000; // תדר דגימה: 16 אלף דגימות לחץ אוויר בשנייה. אופטימלי לדיבור
static constexpr int    N_FFT        = 512;   // גודל התמרת פורייה. חייב להיות חזקה של 2 מטעמי יעילות האלגוריתם.
static constexpr int    WIN_LENGTH   = 400;   // 400 דגימות = בדיוק 25 מילישניות (25ms). בזמן הזה הקול נחשב סטטי פיזיקלית כי מיתרי הקול לא זזים מהר מזה.
static constexpr int    HOP_LENGTH   = 160;   // 160 דגימות = בדיוק 10 מילישניות (10ms). כמות החפיפה. אנחנו קופצים קדימה ב-10ms ולוקחים חלון של 25ms.
static constexpr double F_MIN        = 20.0;  // התדר הנמוך ביותר שאנו סורקים.
static constexpr double F_MAX        = 7600.0;// התדר הגבוה ביותר שאנו סורקים.
static constexpr int    N_MELS       = 80;    // כמות הערוצים הסופית (הפילטרים).
static constexpr double PREEMPH_COEF = 0.97;  // מקדם ההדגשה המוקדמת. מגביר תדרים גבוהים (שם נמצאים העיצורים החשובים לביומטריה).
static constexpr double LOG_FLOOR    = 1e-9;  // מספר זעיר שמוסיפים כדי למנוע קריסה מחישוב מתמטי של Log(0).
static constexpr int    PAD_LEFT     = (N_FFT - WIN_LENGTH) / 2;   // 56. השלמה לאפסים כדי שהחלון של 400 ייכנס למערך של 512 (FFT).
static constexpr int    N_FREQS      = N_FFT / 2 + 1;              // 257. אחרי FFT מקבלים מראה , אז לוקחים רק חצי מהתדרים הרלוונטיים.

static_assert((N_FFT & (N_FFT - 1)) == 0, "N_FFT must be power of 2");
static_assert(PAD_LEFT * 2 + WIN_LENGTH == N_FFT, "window padding mismatch");

// משתנים גלובליים שייבנו רק פעם אחת בטעינת השרת. חוסך זמן חישובף.
static std::vector<double> HAMMING_WIN_D;
static std::vector<double> MEL_FB_D;

// המרת תדרים ליניאריים (הרץ) לתדרי מל (זה המדמה את שמיעת האוזן האנושית)
static inline double hz_to_mel(double hz) {
    return 2595.0 * std::log10(1.0 + hz / 700.0);
}
static inline double mel_to_hz(double mel) {
    return 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0);
}

//  אנחנו מכינים פה את טבלאות הסינוסים והפילטרים
// בזמן שהשרת עולה, כדי שכשאדם ידבר למיקרופון לא נבזבז זמן עיבוד על מתמטיקה קבועה.
static void build_tables()
{
    fft_build_twiddles(N_FFT); // מכין את המתמטיקה של ה-FFT (שורשי יחידה)

    // יצירת חלון חמינג: לוקח קצוות של חלון הקול ומוריד אותם לאפס בעדינות. מונע רעשי רקע
    HAMMING_WIN_D.resize(WIN_LENGTH);
    for (int n = 0; n < WIN_LENGTH; ++n)
        HAMMING_WIN_D[n] = 0.54 - 0.46 * std::cos(2.0 * M_PI * n / WIN_LENGTH);

    const double mel_lo = hz_to_mel(F_MIN);
    const double mel_hi = hz_to_mel(F_MAX);
    std::vector<double> hz_pts(N_MELS + 2);
    for (int i = 0; i < N_MELS + 2; ++i) {
        const double mel = mel_lo + (mel_hi - mel_lo) * i / (N_MELS + 1);
        hz_pts[i] = mel_to_hz(mel);
    }

    std::vector<double> fft_freqs(N_FREQS);
    for (int k = 0; k < N_FREQS; ++k)
        fft_freqs[k] = static_cast<double>(k) * SAMPLE_RATE / N_FFT;

    // בניית מטריצת המשקלים (Mel Filterbank). מקבצת תדרים ביחד.
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
        }
    }
}
/* ═══════════════════════════════════════════════════════════════════
 * extract(audio_np) → ndarray [N_MELS, T]
 * ═══════════════════════════════════════════════════════════════════*/
py::array_t<float> extract(py::array_t<float> audio_np)
{
    // וידוא שהקלט תקין (אורך גדול מ-512) כדי למנוע קריסה של זיכרון ב-C++
    if (audio_np.ndim() != 1)
        throw std::runtime_error("extract: expected 1-D float32 array");

    const int n_samples = static_cast<int>(audio_np.shape(0));

    if (n_samples < N_FFT)
        throw std::runtime_error(
            "extract: audio too short");

    const float* src = audio_np.data();

    // שלב 1: Pre-emphasis (הדגשה מוקדמת)
    // שהיא בעצם מחסרת 97% מהדגימה הקודמת מהנוכחית. פעולה זו מגבירה
    // את התדרים הגבוהים של הקול, שם נמצא המידע על העיצורים שמכיל אפיון ביומטרי קריטי.
    std::vector<double> audio_d(n_samples);
    audio_d[0] = static_cast<double>(src[0]);
    for (int t = 1; t < n_samples; ++t)
        audio_d[t] = static_cast<double>(src[t])
                   - PREEMPH_COEF * static_cast<double>(src[t - 1]);

    // חישוב כמות החלונות שניתן לגזור מההקלטה לפי ה-Hop length (זה הזמן חפיפה)
    const int n_frames = (n_samples - N_FFT) / HOP_LENGTH + 1;

    py::array_t<float> result({N_MELS, n_frames});
    float* out = result.mutable_data();

    std::vector<std::complex<double>> fft_buf(N_FFT);
    std::vector<double> power(N_FREQS);

    // לולאה ראשית הרצה על חלונות הזמן
    for (int fr = 0; fr < n_frames; ++fr) {
        const int frame_start = fr * HOP_LENGTH;
        std::fill(fft_buf.begin(), fft_buf.end(),
                  std::complex<double>(0.0, 0.0));

        // שלב 2: הכפלת החלון בחלון חמינג למניעת קצוות חדים
        for (int n = 0; n < WIN_LENGTH; ++n)
            fft_buf[PAD_LEFT + n] = {
                audio_d[frame_start + PAD_LEFT + n] * HAMMING_WIN_D[n],
                0.0
            };

        // שלב 3: התמרת פורייה (FFT)
        // מעבירים את המידע מממד הזמן לממד התדר עכשיו נוכל לראות איזה
        // תדרים מהדהדים בגרון הייחודי של הדובר
        fft_inplace(fft_buf);

        // מחשבים את עוצמת התדרים לפי משפט פיתגורס על המספרים המרוכבים שחזרו מה-FFT
        for (int k = 0; k < N_FREQS; ++k)
            power[k] = fft_buf[k].real() * fft_buf[k].real()
                     + fft_buf[k].imag() * fft_buf[k].imag();

        // שלב 4: מסנני המל - חיקוי השבלול באוזן
        // זה לוקח את 257 התדרים, ודוחסת אותם ל-80 ערוצים.
        // היא נותנת יותר משקל לתדרים נמוכים ופחות לגבוהים, בדיוק כמו השמיעה האנושית.
        // המשתנה dot צובר את סך העוצמה עבור כל ערוץ מל"
        for (int m = 0; m < N_MELS; ++m) {
            double dot = 0.0;
            const double* row = &MEL_FB_D[m * N_FREQS];
            for (int k = 0; k < N_FREQS; ++k)
                dot += row[k] * power[k];

            // שלב 5: נרמול לוגריתמי - כיד לקבל יציבות הביומטרית
            // הלוגריתם מבטל את עוצמת הקול כמו : צעקה או לחישה
            // שבעקרון ישנו את העוצמה, אבל הוא שומר
            // רק על הפרופורציות והיחס בין התדרים. היחס הזה הוא המבנה הפיזי של
            // הלסת והגרון שלא משתנה.
            out[m * n_frames + fr] =
                static_cast<float>(std::log(dot + LOG_FLOOR));
        }
    }

    return result;   // מחזיר לפייתון מטריצה ביומטרית טהורה במידות 80.
}


/* ═══════════════════════════════════════════════════════════════════
 * pybind11 module definition
 * נקודת החיבור בין ה-C++ ל-Python. מגדיר את המודול כדי שיוכל להיקרא
 * כ- import speaker_features בתוך ה-api.py.
 * ═══════════════════════════════════════════════════════════════════ */
PYBIND11_MODULE(speaker_features, m)
{
    m.doc() = "Thread-safe double-precision Log-Mel Fbank extractor v2";

    build_tables();   // מפעיל את בניית הטבלאות מיד כשהפייתון מייבא (import) את המודול.

    m.def("extract",
          &extract,
          py::arg("audio"),
          R"doc(
Extract Log-Mel Filterbank features.
)doc");

    m.def("get_config",
          &get_config,
          "Return a dict of all compile-time parameters.");
}