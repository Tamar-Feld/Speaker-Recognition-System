import os
import subprocess
import numpy as np
from sklearn.ensemble import RandomForestClassifier
import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_PATH = os.path.join(BASE_DIR, "dataset")
MODEL_FILE = os.path.join(BASE_DIR, "speaker_model.pkl")
CPP_EXE = os.path.join(BASE_DIR, "preprocessor.exe")

def get_features_from_cpp(wav_path):
    csv_path = wav_path + ".csv"

    if not os.path.exists(CPP_EXE):
        print(f"❌ Error: C++ executable not found at {CPP_EXE}")
        return None

    try:
        print(f"   -> Running C++ on: {os.path.basename(wav_path)}")
        # הרצת C++
        result = subprocess.run([CPP_EXE, wav_path], capture_output=True, text=True)

        if result.returncode != 0:
            print(f"   ❌ C++ Failed! Error message:\n{result.stderr}")
            return None

        # בדיקה אם נוצר CSV
        if os.path.exists(csv_path):
            with open(csv_path, 'r') as f:
                content = f.read().strip()
                if not content:
                    print("   ❌ CSV is empty!")
                    return None
                features = [float(x) for x in content.split(',')]

            # בדיקת כמות התכונות
            if len(features) != 13:
                print(f"   ❌ CSV has wrong feature count: {len(features)} (Expected 40)")
                return None

            os.remove(csv_path)
            print("   ✅ Features extracted successfully!")
            return np.array(features)
        else:
            print(f"   ❌ CSV file was not created by C++.")
            return None

    except Exception as e:
        print(f"   ❌ Exception running C++: {e}")
        return None

def train():
    print("=== Starting Detailed Debug Training ===")
    features = []
    labels = []

    if not os.path.exists(DATASET_PATH):
        print("Error: Dataset folder missing!")
        return

    for speaker_name in os.listdir(DATASET_PATH):
        speaker_path = os.path.join(DATASET_PATH, speaker_name)
        if os.path.isdir(speaker_path):
            print(f"\n📂 Scanning folder: {speaker_name}")
            files_found = 0

            for filename in os.listdir(speaker_path):
                file_path = os.path.join(speaker_path, filename)

                # בדיקת סיומת
                if not filename.lower().endswith(".wav"):
                    print(f"   ⚠️ Skipping file (not .wav): {filename}")
                    continue

                files_found += 1
                data = get_features_from_cpp(file_path)

                if data is not None:
                    features.append(data)
                    labels.append(speaker_name)

            if files_found == 0:
                print(f"   ⚠️ No WAV files found in folder: {speaker_name}")

    if len(features) == 0:
        print("\n❌ No valid data collected. Model cannot be trained.")
        return

    print(f"\n🧠 Training model on {len(features)} samples...")
    clf = RandomForestClassifier(n_estimators=100)
    clf.fit(features, labels)

    joblib.dump(clf, MODEL_FILE)
    print(f"✅ SUCCESS: Model saved to {MODEL_FILE}")

if __name__ == "__main__":
    train()