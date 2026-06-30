import os

# קונפיגורציה
OUTPUT_FILE = 'project_architecture_dump.txt'

# מילות מפתח לזיהוי השכבות (לפי שם הקובץ או שם התיקייה)
TARGET_KEYWORDS = ['controller', 'service', 'repository', 'entity', 'entities', 'model']

# תיקיות שיש להתעלם מהן כדי לא לסרוק ספריות חיצוניות או קבצי מערכת
IGNORE_DIRS = set([
    '.git', '.idea', '.vscode', 'node_modules', 'venv', 'env',
    'target', 'bin', 'obj', 'dist', 'build', 'out', '__pycache__'
])

# סיומות קבצים רלוונטיות (כדי לא לנסות לקרוא תמונות או קבצים בינאריים)
VALID_EXTENSIONS = ('.java', '.cs', '.ts', '.js', '.py', '.php', '.cpp', '.h', '.go', '.rb')

def is_target_file(file_name, root_path):
    """
    בודק האם הקובץ שייך לאחת משכבות הארכיטקטורה שביקשנו
    על סמך שם הקובץ או התיקייה שבה הוא נמצא.
    """
    if not file_name.endswith(VALID_EXTENSIONS):
        return False

    name_lower = file_name.lower()
    path_parts = [p.lower() for p in root_path.split(os.sep)]

    # בדיקה אם מילת המפתח נמצאת בשם הקובץ (לדוגמה: UserService.ts)
    for keyword in TARGET_KEYWORDS:
        if keyword in name_lower:
            return True

    # בדיקה אם הקובץ נמצא בתוך תיקייה המייצגת את השכבה (לדוגמה: src/repositories/user.js)
    for keyword in TARGET_KEYWORDS:
        if keyword in path_parts:
            return True

    return False

def generate_architecture_dump():
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as outfile:
        outfile.write("====================================================\n")
        outfile.write("        Project Architecture & Code Dump            \n")
        outfile.write("this is d of 2")
        for root, dirs, files in os.walk('.'):
            # סינון תיקיות שאין צורך לסרוק
            dirs[:] = [d for d in dirs if d not in IGNORE_DIRS]

            for file in files:
                # התעלמות מהסקריפט עצמו וקובץ הפלט
                if file == os.path.basename(__file__) or file == OUTPUT_FILE:
                    continue

                if is_target_file(file, root):
                    file_path = os.path.join(root, file)

                    # כתיבת כותרת מסודרת המציגה את ההיררכיה
                    outfile.write(f"// {'='*70}\n")
                    outfile.write(f"// FILE PATH: {file_path}\n")
                    outfile.write(f"// {'='*70}\n\n")

                    # קריאת תוכן הקובץ וכתיבתו
                    try:
                        with open(file_path, 'r', encoding='utf-8', errors='ignore') as infile:
                            content = infile.read()
                            outfile.write(content)
                            if not content.endswith('\n'):
                                outfile.write('\n')
                    except Exception as e:
                        outfile.write(f"/* Error reading file: {e} */\n")

                    outfile.write("\n\n")

if __name__ == "__main__":
    print(f"Starting to scan the project... Output will be saved to '{OUTPUT_FILE}'")
    generate_architecture_dump()