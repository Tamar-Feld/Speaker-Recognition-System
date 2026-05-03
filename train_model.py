import os
import subprocess
import numpy as np
from sklearn.neural_network import MLPClassifier # <--- רשת הנוירונים העמוקה!
from sklearn.preprocessing import StandardScaler
import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_PATH = os.path.join(BASE_DIR, "dataset")
MODEL_FILE = os.path.join(BASE_DIR, "speaker_dnn_model.pkl") # שינינו שם
SCALER_FILE = os.path.join(BASE_DIR, "speaker_scaler.pkl")   # קובץ חדש לנרמול
CPP_EXE = os.path.join(BASE_DIR, "preprocessor.exe")

def get_features_from_cpp(wav_path):
    csv_path = wav_path + ".csv"
    if not os.path.exists(CPP_EXE):
        return None
    try:
        subprocess.run([CPP_EXE, wav_path], check=True, stdout=subprocess.DEVNULL)
        if os.path.exists(csv_path):
            with open(csv_path, 'r') as f:
                content = f.read().strip()
                if not content: return None
                features = [float(x) for x in content.split(',')]
            os.remove(csv_path)

            # בדיקת תקינות (אנחנו מצפים ל-40 מ-C++)
            if len(features) == 40:
                return np.array(features)
        return None
    except Exception:
        return None

def train():
    print("=== Starting Deep Learning Training (DNN) ===")
    features = []
    labels =[]

    if not os.path.exists(DATASET_PATH):
        print("Error: Dataset missing")
        return

    # 1. איסוף נתונים
    for speaker_name in os.listdir(DATASET_PATH):
        speaker_path = os.path.join(DATASET_PATH, speaker_name)
        if os.path.isdir(speaker_path):
            for filename in os.listdir(speaker_path):
                if filename.lower().endswith(".wav"):
                    file_path = os.path.join(speaker_path, filename)
                    data = get_features_from_cpp(file_path)
                    if data is not None:
                        features.append(data)
                        labels.append(speaker_name)

    if not features:
        print("No data found.")
        return

    X = np.array(features)
    y = np.array(labels)

    # 2. נרמול הנתונים (קריטי לרשתות נוירונים!)
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    # 3. בניית רשת הנוירונים העמוקה (Deep Neural Network)
    # hidden_layer_sizes=(100, 50) אומר: שכבה נסתרת ראשונה 100 נוירונים, שנייה 50 נוירונים.
    # max_iter=1000 נותן לרשת מספיק זמן ללמוד (Backpropagation)
    print("🧠 Building and training Neural Network...")
    clf = MLPClassifier(hidden_layer_sizes=(100, 50), activation='relu', solver='adam', max_iter=1000, random_state=42)
    clf.fit(X_scaled, y)

    # 4. שמירת המודל והמנרמל
    joblib.dump(clf, MODEL_FILE)
    joblib.dump(scaler, SCALER_FILE)
    print(f"✅ SUCCESS: Deep Learning Model saved!")

if __name__ == "__main__":
    train()