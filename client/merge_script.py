"""
scan_project.py  —  הרץ אותו מתוך תיקיית הפרויקט:
    python scan_project.py
"""
import os, sys

IGNORE = {'node_modules', '.git', 'dist', 'build', '.next',
          '__pycache__', '.vite', 'coverage', '.turbo'}

ALL_EXT  = {'.jsx', '.tsx', '.js', '.ts', '.css', '.html', '.json', '.env',
            '.yaml', '.yml', '.config', '.cjs', '.mjs'}

# קבצי JSON שנרצה לקרוא (לא node_modules)
READ_JSON = {'package.json', 'tsconfig.json', 'vite.config.js',
             'vite.config.ts', '.env', '.env.local'}

OUTPUT = 'project_scan.txt'

root = '.'

def walk(path, prefix=''):
    items = sorted(os.listdir(path))
    dirs  = [i for i in items if os.path.isdir(os.path.join(path, i)) and i not in IGNORE]
    files = [i for i in items if os.path.isfile(os.path.join(path, i))]
    lines = []
    for i, d in enumerate(dirs):
        connector = '└── ' if (i == len(dirs)-1 and not files) else '├── '
        lines.append(f'{prefix}{connector}📁 {d}/')
        ext_pfx = '    ' if connector.startswith('└') else '│   '
        lines += walk(os.path.join(path, d), prefix + ext_pfx)
    for i, f in enumerate(files):
        connector = '└── ' if i == len(files)-1 else '├── '
        lines.append(f'{prefix}{connector}{f}')
    return lines

with open(OUTPUT, 'w', encoding='utf-8') as out:

    # 1. עץ הקבצים המלא
    out.write('='*60 + '\n')
    out.write('PROJECT TREE\n')
    out.write('='*60 + '\n')
    out.write(f'{os.path.abspath(root)}\n')
    for line in walk(root):
        out.write(line + '\n')

    # 2. תוכן כל קבצי ה-JSX / TSX / JS / TS
    out.write('\n\n' + '='*60 + '\n')
    out.write('SOURCE FILES CONTENT (.jsx / .tsx / .js / .ts / .css)\n')
    out.write('='*60 + '\n')

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in IGNORE]
        for fname in sorted(filenames):
            ext = os.path.splitext(fname)[1].lower()
            full = os.path.join(dirpath, fname)
            rel  = os.path.relpath(full, root)

            if ext in {'.jsx', '.tsx', '.js', '.ts', '.css'} or fname in READ_JSON:
                # דלג על קבצי CSS גדולים יותר מ-8KB (לא components)
                size = os.path.getsize(full)
                if ext == '.css' and size > 8000:
                    out.write(f'\n\n--- {rel}  [{size} bytes — נחתך] ---\n')
                    continue

                out.write(f'\n\n{"="*50}\n')
                out.write(f'FILE: {rel}\n')
                out.write(f'{"="*50}\n')
                try:
                    with open(full, 'r', encoding='utf-8', errors='ignore') as f:
                        out.write(f.read())
                except Exception as e:
                    out.write(f'[שגיאה: {e}]\n')

print(f'✅  הסריקה הושלמה: {os.path.abspath(OUTPUT)}')
print(f'    גודל: {os.path.getsize(OUTPUT):,} bytes')
print(f'    שתף את הקובץ כאן ↑')