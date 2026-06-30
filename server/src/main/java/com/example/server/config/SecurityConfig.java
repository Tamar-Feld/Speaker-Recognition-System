package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// ═══════════════════════════════════════════════════════════════════
// SecurityConfig.java — ליבת האבטחה של השרת
//
// ארכיטקטורה (Spring Security 6.x):
// - מחליף לחלוטין את ה"מעקף" של SecurityBeansConfig.
// - מגדיר מדיניות Stateless (אין Sessions בזיכרון, רק JWT).
// - ניתוב (Routing): פותח את נקודות הקצה הציבוריות (ביומטריה ולוגין),
//   ונועל את כל שאר ה-API.
// - CORS: מוגדר גלובלית ברמת ה-Security כדי לאפשר ל-React לגשת לשרת.
// ═══════════════════════════════════════════════════════════════════

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final com.example.server.config.JwtFilter jwtFilter;

    // הזרקת ה-Filter שיבדוק כל בקשה נכנסת
    public SecurityConfig(com.example.server.config.JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. ביטול הגנת CSRF (כי אנחנו משתמשים ב-JWT ואין לנו Cookies)
                .csrf(AbstractHttpConfigurer::disable)

                // 2. הפעלת CORS כדי ש-React יוכל לדבר עם השרת שלנו (מוגדר למטה)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 3. הגדרת ניהול Sessions כ-Stateless (כל בקשה נבדקת מחדש לפי ה-JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 4. חוקי הגישה (Authorization) - מה פתוח ומה סגור
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/login").permitAll()  // פתוח לכולם - כדי לקבל טוקן
                        .requestMatchers("/api/audio/upload").permitAll() // פתוח לכולם - דלת הכניסה הביומטרית
                        .requestMatchers("/error").permitAll()            // פתוח - למקרה של שגיאות פנימיות
                        .anyRequest().authenticated()                     // כל שאר השרת - חסום ללא JWT!
                )

                // 5. שילוב ה-JwtFilter שלנו לפני הפילטר הרגיל של Spring Security
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * הצפנת סיסמאות BCrypt (מחליף את הקוד שהיה ב-SecurityBeansConfig)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * הגדרת CORS מקצועית למניעת שגיאות בחיבור עם ה-Frontend (React)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // שימוש ב-OriginPatterns במקום AllowedOrigins מאפשר לשלב allowCredentials בצורה בטוחה
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true); // הכרחי להעברת Headers מתקדמים

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // החל על כל השרת
        return source;
    }
}