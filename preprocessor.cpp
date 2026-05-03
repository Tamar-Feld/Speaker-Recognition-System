#include <iostream>
#include <vector>
#include <fstream>
#include <cmath>
#include <complex>
#include <iomanip>
#include <algorithm>
#include <cstring>
#include <cstdint> //
using namespace std;

// === הגדרות ===

const int NUM_FEATURES = 52; // מספר הערכים שייכתבו ל-CSV

const int FRAME_SIZE = 1024;

const int HOP_SIZE = 512;

const double PI = 3.141592653589793238460;

const int LPC_ORDER = 14;

// עדיף לממש את החיפוש למילה דאטה כדי לא לקחת סיכון אם הפורמט לא יהיה ממש זהה

struct WavHeader {

char riff[4]; // "RIFF"

uint32_t fileSize; // גודל הקובץ פחות 8 בתים

char wave[4]; // "WAVE"

char fmt[4]; // "fmt "

uint32_t fmtLen; // גודל בלוק הפורמט (בד"כ 16)

uint16_t formatTag; // 1 עבור PCM (לא דחוס)

uint16_t channels; // 1 למונו, 2 לסטריאו

uint32_t sampleRate; // תדר דגימה (למשל 44100)

uint32_t byteRate; // (SampleRate * Channels * BitsPerSample) / 8

uint16_t blockAlign; // (Channels * BitsPerSample) / 8

uint16_t bitsPerSample; // 8, 16, 24 וכו'

char dataTag[4]; // "data" - כאן מתחיל גוש הנתונים

uint32_t dataLen; // גודל נתוני השמע בבתים

};

// 1. פילטר Pre-emphasis
// מגביר את התדרים הגבוהים כדי לפצות על הדעיכה הטבעית שלהם בדיבור.
// משפר משמעותית את התוצאות גם ב-LPC וגם ב-MFCC.
vector<double> applyPreEmphasis(const vector<double>& signal, double alpha = 0.97) {
    if (signal.empty()) return {};

    vector<double> emphasized(signal.size());
    emphasized[0] = signal[0]; // הדגימה הראשונה נשארת ללא שינוי

    for (size_t i = 1; i < signal.size(); ++i) {
        emphasized[i] = signal[i] - alpha * signal[i - 1];
    }

    return emphasized;
}
// 2. חלוקה למסגרות (Framing)
// לוקח את האות הארוך ומחלק אותו למסגרות קצרות עם חפיפה (Overlap).
vector<vector<double>> createFrames(const vector<double>& signal, int frameSize, int hopSize) {
    vector<vector<double>> frames;

    // חישוב מספר המסגרות שאפשר לייצר
    int numFrames = 1 + (signal.size() - frameSize) / hopSize;
    if (numFrames <= 0) return frames;

    for (int i = 0; i < numFrames; ++i) {
        int startIdx = i * hopSize;
        // העתקת החלק הרלוונטי מהאות לתוך המסגרת
        vector<double> frame(signal.begin() + startIdx, signal.begin() + startIdx + frameSize);
        frames.push_back(frame);
    }

    return frames;
}
// 3. יצירת חלון Hamming
// מחשב מראש את חלון ההאמינג. כדאי לקרוא לפונקציה פעם אחת ולשמור את התוצאה
// כדי לא לחשב קוסינוסים מחדש עבור כל מסגרת.
vector<double> getHammingWindow(int frameSize) {
    vector<double> window(frameSize);
    for (int i = 0; i < frameSize; ++i) {
        window[i] = 0.54 - 0.46 * cos(2.0 * PI * i / (frameSize - 1));
    }
    return window;
}
// 4. הפעלת החלון על מסגרת (In-place)
// מכפיל כל דגימה במסגרת בערך המתאים מחלון ה-Hamming.
void applyWindowToFrame(vector<double>& frame, const vector<double>& window) {
    for (size_t i = 0; i < frame.size(); ++i) {
        frame[i] *= window[i];
    }
}
// המרה מתדר (Hz) ל-Mel
double hzToMel(double hz) {
    return 2595.0 * log10(1.0 + hz / 700.0);
}

// המרה מ-Mel לתדר (Hz)
double melToHz(double mel) {
    return 700.0 * (pow(10.0, mel / 2595.0) - 1.0);
}
// חישוב Power Spectrum (ריבוע הערך המוחלט של ה-FFT)
vector<double> computePowerSpectrum(const vector<complex<double>>& fftResult) {
    int n = fftResult.size() / 2 + 1; // חצי מהספקטרום מספיק (סימטריה)
    vector<double> power(n);
    for (int i = 0; i < n; i++) {
        power[i] = norm(fftResult[i]) / fftResult.size();
    }
    return power;
}
// יצירת מטריצת מסנני Mel
vector<vector<double>> getMelFilterbank(int numFilters, int fftSize, int sampleRate) {
    double minMel = hzToMel(0);
    double maxMel = hzToMel(sampleRate / 2);

    vector<double> melPoints(numFilters + 2);
    for (int i = 0; i < numFilters + 2; i++) {
        melPoints[i] = melToHz(minMel + i * (maxMel - minMel) / (numFilters + 1));
    }

    // המרת התדרים לאינדקסים בתוך ה-FFT
    vector<int> binPoints(numFilters + 2);
    for (int i = 0; i < numFilters + 2; i++) {
        binPoints[i] = floor((fftSize /2+ 1) * melPoints[i] / sampleRate/2);
    }

    vector<vector<double>> filters(numFilters, vector<double>(fftSize / 2 + 1, 0.0));
    for (int m = 1; m <= numFilters; m++) {
        for (int k = binPoints[m - 1]; k < binPoints[m]; k++) {
            filters[m - 1][k] = (double)(k - binPoints[m - 1]) / (binPoints[m] - binPoints[m - 1]);
        }
        for (int k = binPoints[m]; k < binPoints[m + 1]; k++) {
            filters[m - 1][k] = (double)(binPoints[m + 1] - k) / (binPoints[m + 1] - binPoints[m]);
        }
    }
    return filters;
}
// פונקציית FFT
void fft(vector<complex<double>>& a) {
int n = a.size();
if (n <= 1) return;
vector<complex<double>> even(n / 2), odd(n / 2);
for (int i = 0; i < n / 2; i++) {
even[i] = a[2 * i];
odd[i] = a[2 * i + 1];
}
fft(even);
fft(odd);
for (int i = 0; i < n / 2; i++) {
// הנוסחה: exp(-2 * PI * i / n)
complex<double> t = polar(1.0, -2 * PI * i / n) * odd[i];
a[i] = even[i] + t;
a[i + n / 2] = even[i] - t;
}
}
//  חישוב אוטוקורלציה
vector<double> computeAutocorr(const vector<double>& frame, int p) {
    vector<double> r(p + 1, 0.0);
    int N = frame.size();
    for (int k = 0; k <= p; k++) {
        for (int n = 0; n < N - k; n++) {
            r[k] += frame[n] * frame[n + k];
        }
    }
    return r;
}

//  אלגוריתם לוינסון-דרבין לחילוץ מקדמי LPC
vector<double> levinsonDurbin(const vector<double>& r, int p) {
    vector<double> a(p + 1, 0.0);
    vector<double> k_coeffs(p + 1, 0.0);
    double error = r[0];
    a[0] = 1.0;

    for (int i = 1; i <= p; i++) {
        double epsilon = r[i];
        for (int j = 0; j < i; j++) {
            epsilon += a[j] * r[i - j];
        }
        double ki = -epsilon / error;

        // עדכון המקדמים
        vector<double> a_next = a;
        for (int j = 1; j < i; j++) {
            a_next[j] = a[j] + ki * a[i - j];
        }
        a_next[i] = ki;
        a = a_next;

        error *= (1.0 - ki * ki);
    }
    return a; // מחזיר וקטור עם p+1 מקדמים (הראשון תמיד 1)
}
// DCT
vector<double> applyDCT(const vector<double>& filterEnergies) {
    int numCoeffs = filterEnergies.size();
    vector<double> mfccs(numCoeffs, 0.0);
    for (int i = 0; i < numCoeffs; i++) {
        for (int j = 0; j < numCoeffs; j++) {
            mfccs[i] += filterEnergies[j] * cos(PI * i * (j + 0.5) / numCoeffs);
        }
    }
    return mfccs;
}



int main(int argc, char* argv[]) {

// קבלת שמות הקבצים
if (argc < 2) return 1;
string inputPath = argv[1];
string outputPath = inputPath + ".csv"; // שם קובץ הפלט
ifstream inFile(inputPath, ios::binary);
if (!inFile) {
std::cerr << "Error opening file" << endl;
return 1;

}

// חיפוש חכם של הנתונים (DATA chunk)

char chunkId[5] = {0};
uint32_t chunkSize;
bool dataFound = false;

// מדלגים על ה-12 בייטים הראשונים

inFile.seekg(12, ios::beg);

while (inFile.read(chunkId, 4) && inFile.read(reinterpret_cast<char*>(&chunkSize), 4)) {
if (strncmp(chunkId, "data", 4) == 0) {
dataFound = true;
break;
}
inFile.seekg(chunkSize, ios::cur);
}

if (!dataFound) {
std::cerr << "Error: No 'data' chunk found." << std::endl;
return 1;
}

//  קריאת האודיו

int numSamples = chunkSize / 2;
vector<int16_t> rawSamples(numSamples);
inFile.read(reinterpret_cast<char*>(rawSamples.data()), chunkSize);
inFile.close();
outFile.close();
cout << "SUCCESS: Generated CSV: " << outputPath << endl;

return 0;

}