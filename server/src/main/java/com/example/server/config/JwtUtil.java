package com.example.server.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

// ═══════════════════════════════════════════════════════════════════
// JwtUtil.java — ניהול אסימוני אבטחה (JSON Web Tokens)
//
// ארכיטקטורה:
// - הזרקת מפתח סודי (Secret Key) בצורה מאובטחת מקובץ ההגדרות,
//   עם Fallback למשתנה סביבה (Environment Variable) כדי למנוע קידוד קשיח.
// - המרת המחרוזת לאובייקט Key יציב (HMAC-SHA), כך שהטוקנים לא
//   יימחקו/יפסלו בכל הפעלה מחדש של השרת.
// ═══════════════════════════════════════════════════════════════════

@Component
public class JwtUtil {

    // אובייקט המפתח הסופי שאיתו נחתום ונפענח את הטוקנים
    private final Key key;

    // תוקף הטוקן - נקבע ל-10 שעות (מוצג במילישניות)
    private final long TOKEN_VALIDITY = 1000 * 60 * 60 * 10;

    /**
     * הזרקת הערך מקובץ ההגדרות בזמן עליית השרת (Constructor Injection).
     * משתמש בערך גיבוי מאובטח וארוך (לפחות 32 תווים) במידה ולא הוגדר.
     */
    public JwtUtil(@Value("${jwt.secret:${JWT_SECRET:fallback-dev-secret-key-must-be-long-enough-32-chars}}") String secretKey) {
        // UTF-8 מפורש — מבטיח אותו מפתח בין מכונות בעלות JVM charset שונה
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    // 1. הפקת טוקן חדש עבור שם המשתמש של המנהל
    public String generateToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY))
                .signWith(key, SignatureAlgorithm.HS256) // שימוש במפתח היציב שהמרנו
                .compact();
    }

    // 2. חילוץ שם המשתמש מתוך הטוקן שנדחף ב-Header
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    // 3. חילוץ תאריך התפוגה של הטוקן
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key) // שימוש במפתח היציב לפיענוח
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // 4. בדיקה האם הטוקן פג תוקף
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // 5. ולידציה מלאה: האם הטוקן שייך למנהל והאם הוא עדיין בתוקף
    public Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }
}