import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

// יצירת משתני סביבה חלופיים עבור ES Modules
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// נתיב לתיקיית המקור
const srcDir = path.join(__dirname, 'src');

// פונקציה לסריקה רקורסיבית של קבצים
function scanFiles(dir, fileList = []) {
    if (!fs.existsSync(dir)) return fileList;

    const files = fs.readdirSync(dir);
    files.forEach(file => {
        const filePath = path.join(dir, file);
        if (fs.statSync(filePath).isDirectory()) {
            scanFiles(filePath, fileList);
        } else if (/\.(jsx?|tsx?)$/.test(file)) {
            fileList.push(filePath);
        }
    });
    return fileList;
}

const files = scanFiles(srcDir);
let results = "תוצאות סריקת ניתובים ומעברים - React Router:\n==============================================\n";

files.forEach(file => {
    const content = fs.readFileSync(file, 'utf8');
    const fileName = path.basename(file);
    let hasRouting = false;
    let fileOutput = `\n--- קובץ: ${fileName} ---\n`;

    // 1. איתור הגדרות מסכים (Routes)
    const routeRegex = /<Route[^>]*path=['"]([^'"]+)['"][^>]*element=\{?<([A-Za-z0-9_]+)/g;
    let routeMatch;
    while ((routeMatch = routeRegex.exec(content)) !== null) {
        hasRouting = true;
        fileOutput += `📍 מסך מוצג: רכיב <${routeMatch[2]}> משויך לנתיב: ${routeMatch[1]}\n`;
    }

    // 2. איתור מעברים מסוג useNavigate
    const navigateRegex = /navigate\(['"`]([^'"`]+)['"`]/g;
    let navMatch;
    while ((navMatch = navigateRegex.exec(content)) !== null) {
        hasRouting = true;
        fileOutput += `🚀 מעבר אקטיבי לנתיב: ${navMatch[1]}\n`;
    }

    // 3. איתור מעברים מסוג <Navigate to="..." />
    const navigateCompRegex = /<Navigate[^>]*to=['"]([^'"]+)['"]/g;
    let navCompMatch;
    while ((navCompMatch = navigateCompRegex.exec(content)) !== null) {
        hasRouting = true;
        fileOutput += `🔄 הפניה אוטומטית (Redirect/Fallback) לנתיב: ${navCompMatch[1]}\n`;
    }

    // 4. איתור קישורים מסוג <Link to="..." />
    const linkRegex = /<Link[^>]*to=['"]([^'"]+)['"]/g;
    let linkMatch;
    while ((linkMatch = linkRegex.exec(content)) !== null) {
        hasRouting = true;
        fileOutput += `🔗 קישור משתמש (Link) לנתיב: ${linkMatch[1]}\n`;
    }

    if (hasRouting) {
        results += fileOutput;
    }
});

fs.writeFileSync('routes_output.txt', results);
console.log("✅ הסריקה הסתיימה בהצלחה! הנתונים נשמרו בקובץ: routes_output.txt");