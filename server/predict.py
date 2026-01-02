import sys
import os
import numpy as np
import joblib

# טעינת המודל המוכן
MODEL_FILE = "speaker_model.pkl"


if __name__ == "__main__":
    # בדיקה שהמודל קיים
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(script_dir, MODEL_FILE)

    if not os.path.exists(model_path):
        print("Error: Model file not found. Run train_model.py first.")
        sys.exit(1)

    # טעינת המודל לזיכרון
    clf = joblib.load(model_path)

    # קבלת הקובץ מה-Java
    if len(sys.argv) < 2:
        print("Error: No file provided")
        sys.exit(1)
    csv_file = sys.argv[1] # מקבלים את הנתיב ל-CSV מהג'אווה

    # קריאת המספרים מתוך הקובץ ש-C++ יצר
    try:
        with open(csv_file, 'r') as f:
            content = f.read().strip() # קורא את הטקסט
            # הופך את הטקסט "0.5,0.2,0.1" לרשימה של מספרים אמיתיים
            features = [float(x) for x in content.split(',')]
    except Exception as e:
        print(f"Error reading CSV: {e}")
        sys.exit(1)

    # בדיקת בטיחות: האם באמת קיבלנו 40 מספרים?
    if len(features) != 13:
        print(f"Error: Expected 40 features from C++, got {len(features)}")
        sys.exit(1)
    prediction = clf.predict([features])[0]

    # חישוב הסתברות (כמה המודל בטוח?)
    probabilities = clf.predict_proba([features])[0]
    confidence = np.max(probabilities) * 100

# פורמט נקי: שם,נקודותיים,אחוזים
print(f"RESULT:{prediction}:{confidence:.2f}")