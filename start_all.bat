@echo off
chcp 65001 >nul

:: ====== הגדר כאן את נתיב Java שלך ======
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot:: ==========================================

set PATH=%JAVA_HOME%\bin;%PATH%

echo ==========================================
echo   SpeakerAuth - Starting All Services
echo ==========================================

echo [1/3] Starting Spring Boot (port 8080)...
start "Spring Boot" cmd /k "set JAVA_HOME=%JAVA_HOME% && set PATH=%JAVA_HOME%\bin;%PATH% && cd /d C:\Users\WIN 11\Documents\SpeakerAuth\server && mvnw spring-boot:run"

timeout /t 3 /nobreak >nul

echo [2/3] Starting FastAPI (port 8000)...
start "FastAPI" cmd /k "cd /d C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine && python api.py"

timeout /t 3 /nobreak >nul

echo [3/3] Starting React Client (port 3000)...
start "React Client" cmd /k "cd /d C:\Users\WIN 11\Documents\SpeakerAuth\client && npm run dev"

echo.
echo All 3 services launching in separate windows.
echo Spring Boot : http://localhost:8080
echo FastAPI     : http://localhost:8000
echo React       : http://localhost:3000
echo.
pause