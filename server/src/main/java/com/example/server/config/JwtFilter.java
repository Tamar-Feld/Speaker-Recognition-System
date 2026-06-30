package com.example.server.config;

import com.example.server.config.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

// ═══════════════════════════════════════════════════════════════════
// JwtFilter.java — פילטר יירוט בקשות (The Bouncer)
//
// תפקיד:
// פילטר זה יושב בכניסה לשרת (OncePerRequestFilter). על כל בקשת HTTP
// נכנסת הוא מוודא: האם יש "Authorization: Bearer <token>" ב-Header?
// אם כן, הוא מפענח אותו מול ה-JwtUtil שלנו. אם הטוקן תקין, הוא מכניס
// את המשתמש ל-SecurityContext של ספרינג, והבקשה מורשית לעבור.
// ═══════════════════════════════════════════════════════════════════

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 1. קריאת כותרת ה-Authorization מהבקשה
        final String authHeader = request.getHeader("Authorization");

        // 2. אם אין כותרת, או שהיא לא מתחילה ב-"Bearer ", מעבירים הלאה.
        // אם הנתיב דורש אבטחה - Spring יחסום את הבקשה.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. חילוץ המחרוזת של הטוקן (הסרת 7 התווים של "Bearer ")
        final String jwt = authHeader.substring(7);

        try {
            // 4. חילוץ שם המשתמש מהטוקן
            final String username = jwtUtil.extractUsername(jwt);

            // 5. אם חילצנו שם, ועוד לא הגדרנו אותו כ"מחובר" בבקשה הנוכחית
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 6. וידוא תוקף וחתימה
                if (jwtUtil.validateToken(jwt, username)) {

                    // 7. יצירת אובייקט "הזדהות מוצלחת" חסר הרשאות ספציפיות (מספיק לנו שיהיה מחובר)
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username, null, new ArrayList<>()
                    );

                    // 8. עדכון ה-Context של ספרינג - "המשתמש הזה מורשה!"
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // אם הטוקן פג תוקף או מזויף, JJWT יזרוק חריגה.
            // אנחנו פשוט תופסים אותה ולא מאשרים את הבקשה (המשתמש יישאר אנונימי ויידחה).
            logger.warn("Invalid JWT token detected: " + e.getMessage());
        }

        // 9. המשך שרשרת הפילטרים הרגילה
        filterChain.doFilter(request, response);
    }
}