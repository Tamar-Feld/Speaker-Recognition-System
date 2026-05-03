import sys
import os
import numpy as np
import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_FILE = os.path.join(BASE_DIR, "speaker_dnn_model.pkl")
SCALER_FILE = os.path.join(BASE_DIR, "speaker_scaler.pkl")

if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(1)

    csv_file = sys.argv[1]

    if not os.path.exists(MODEL_FILE) or not os.path.exists(SCALER_FILE):
        print("RESULT:Error:Model not trained:0")
        sys.exit(1)

    try:
        # 1. קריאת הנתונים
        with open(csv_file, 'r') as f:
            content = f.read().strip()
            features = [float(x) for x in content.split(',')]

        if len(features) != 40:
            sys.exit(1)

        input_vector = np.array(features).reshape(1, -1)

        # 2. טעינת רשת הנוירונים והמנרמל
        clf = joblib.load(MODEL_FILE)
        scaler = joblib.load(SCALER_FILE)

        # 3. נרמול הנתון החדש בדיוק כמו באימון!
        scaled_vector = scaler.transform(input_vector)

        # 4. חיזוי באמצעות רשת הנוירונים
        prediction = clf.predict(scaled_vector)[0]

        # חישוב אחוזי הביטחון (Softmax layer output)
        probabilities = clf.predict_proba(scaled_vector)[0]
        confidence = np.max(probabilities) * 100

        # הדפסה בפורמט שה-Java מחפש
        print(f"RESULT:{prediction}:{confidence:.2f}")

    except Exception as e:
        print(f"Error: {e}")