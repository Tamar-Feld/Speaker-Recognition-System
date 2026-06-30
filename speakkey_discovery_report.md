# דוח גילוי פרויקט SpeakKey — 2026-06-22T03:45:20.070130

**שורש שנסרק:** `C:\Users\WIN 11\Documents\SpeakerAuth`  
**סה"כ קבצים:** 64

## תיקיות לפי גודל (רמה ראשונה)

- `ai_engine` — 26.1MB
- `client` — 270.2KB
- `shared_data` — 250.1KB
- `research` — 64.4KB
- `server` — 55.2KB
- `test.py` — 20.7KB
- `diag_2_code.py` — 17.1KB
- `CLAUDE.md` — 14.8KB
- `diag_1_schema.py` — 10.4KB
- `start_all.bat` — 1.1KB

## תתי-פרויקטים שזוהו

### `ai_engine` — Python
- קובץ סימן: `requirements.txt`
- פרטים: ```["fastapi", "uvicorn[standard]", "numpy", "soundfile", "librosa", "torch"]```

### `client` — Node / React / Vite
- קובץ סימן: `package.json`
- פרטים: ```{"name": "speakkey-client", "version": "1.0.0", "scripts": {"dev": "vite", "build": "vite build", "preview": "vite preview"}, "dependencies": ["axios", "react", "react-dom", "react-router-dom"], "devDependencies": ["@vitejs/plugin-react", "vite"]}```

### `server` — Java / Maven (כנראה Spring Boot)
- קובץ סימן: `pom.xml`
- פרטים: ```{"groupId": "com.example", "artifactId": "SpeakerAuth", "version": "0.0.1-SNAPSHOT", "java_version": null, "dependencies": ["org.springframework.boot:spring-boot-starter-data-jpa", "org.springframework.boot:spring-boot-starter-web", "com.mysql:mysql-connector-j", "org.projectlombok:lombok", "org.springframework.boot:spring-boot-starter-test"]}```

## קונפיגורציית DB שנמצאה

- קובץ: `ai_engine\.env`
  - raw_path: `C:\Users\WIN 11\Documents\SpeakerAuth\ai_engine\.env`
  - username: `root`
  - password_masked: `****`
  - host: `localhost`
  - port: `3306`
  - dbname: `speaker_db`
- קובץ: `server\src\main\resources\application.properties`
  - raw_path: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\resources\application.properties`
  - jdbc_url: `jdbc:mysql://localhost:3306/speaker_db?createDatabaseIfNotExist=true&serverTimezone=UTC`
  - username: `root`
  - password_masked: `****`

## אינטרוספקציית MySQL חיה

סטטוס: **attempted**

### ai_engine\.env → DB `speaker_db`
סטטוס: ok

**טבלה: `access_logs`** (שורות: 20)
```sql
CREATE TABLE `access_logs` (
  `access_granted` bit(1) NOT NULL,
  `confidence` double NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `processing_time_ms` bigint DEFAULT NULL,
  `timestamp` datetime(6) NOT NULL,
  `room_number` int NOT NULL,
  `ip_address` varchar(45) DEFAULT NULL,
  `identified_speaker` varchar(100) DEFAULT NULL,
  `rejection_reason` varchar(500) DEFAULT NULL,
  `filename` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_timestamp` (`timestamp`),
  KEY `idx_speaker` (`identified_speaker`),
  KEY `idx_granted` (`access_granted`),
  KEY `idx_al_room_number` (`room_number`),
  CONSTRAINT `chk_al_room_number` CHECK ((`room_number` between 1 and 6))
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

**טבלה: `admins`** (שורות: 1)
```sql
CREATE TABLE `admins` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_login` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_admin_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

**טבלה: `room_permissions`** (שורות: 7)
```sql
CREATE TABLE `room_permissions` (
  `room_number` int NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_room` (`username`,`room_number`),
  CONSTRAINT `fk_rp_username` FOREIGN KEY (`username`) REFERENCES `users` (`username`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `chk_rp_room_number` CHECK ((`room_number` between 1 and 6))
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

**טבלה: `users`** (שורות: 4)
```sql
CREATE TABLE `users` (
  `is_authorized` bit(1) NOT NULL,
  `created_at` datetime NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  `full_name` varchar(100) NOT NULL,
  `biometric_embedding` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKr43af9ap4edm43mmtq01oddj6` (`username`),
  KEY `idx_biometric_embedding` (`biometric_embedding`(255))
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### server\src\main\resources\application.properties → DB `speaker_db`
סטטוס: ok

**טבלה: `access_logs`** (שורות: 20)
```sql
CREATE TABLE `access_logs` (
  `access_granted` bit(1) NOT NULL,
  `confidence` double NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `processing_time_ms` bigint DEFAULT NULL,
  `timestamp` datetime(6) NOT NULL,
  `room_number` int NOT NULL,
  `ip_address` varchar(45) DEFAULT NULL,
  `identified_speaker` varchar(100) DEFAULT NULL,
  `rejection_reason` varchar(500) DEFAULT NULL,
  `filename` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_timestamp` (`timestamp`),
  KEY `idx_speaker` (`identified_speaker`),
  KEY `idx_granted` (`access_granted`),
  KEY `idx_al_room_number` (`room_number`),
  CONSTRAINT `chk_al_room_number` CHECK ((`room_number` between 1 and 6))
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

**טבלה: `admins`** (שורות: 1)
```sql
CREATE TABLE `admins` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_login` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_admin_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

**טבלה: `room_permissions`** (שורות: 7)
```sql
CREATE TABLE `room_permissions` (
  `room_number` int NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_room` (`username`,`room_number`),
  CONSTRAINT `fk_rp_username` FOREIGN KEY (`username`) REFERENCES `users` (`username`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `chk_rp_room_number` CHECK ((`room_number` between 1 and 6))
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

**טבלה: `users`** (שורות: 4)
```sql
CREATE TABLE `users` (
  `is_authorized` bit(1) NOT NULL,
  `created_at` datetime NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  `full_name` varchar(100) NOT NULL,
  `biometric_embedding` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKr43af9ap4edm43mmtq01oddj6` (`username`),
  KEY `idx_biometric_embedding` (`biometric_embedding`(255))
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## ⚠️ סודות פוטנציאליים שנמצאו בקבצים (לבדוק לפני ZIP!)

- `C:\Users\WIN 11\Documents\SpeakerAuth\CLAUDE.md:78` [password] → `spring.datasource.password=1234`
- `C:\Users\WIN 11\Documents\SpeakerAuth\diag_1_schema.py:106` [password] → `user=user, password=pwd,`
- `C:\Users\WIN 11\Documents\SpeakerAuth\test.py:259` [password] → `password = cfg.get("_password_raw_for_internal_connect_only")`
- `C:\Users\WIN 11\Documents\SpeakerAuth\test.py:280` [password] → `conn = mysql.connector.connect(host=host, port=port, user=user, password=password, database=dbname)`
- `C:\Users\WIN 11\Documents\SpeakerAuth\client\project_scan.txt:331` [password] → `const ADMIN_PASSWORD = 'admin123'`
- `C:\Users\WIN 11\Documents\SpeakerAuth\client\project_scan.txt:381` [password] → `const ADMIN_PASSWORD = 'admin123'`
- `C:\Users\WIN 11\Documents\SpeakerAuth\client\src\context\AdminAuthContext.jsx:5` [password] → `const ADMIN_PASSWORD = 'admin123'`
- `C:\Users\WIN 11\Documents\SpeakerAuth\client\src\hooks\useAdminAuth.js:8` [password] → `const ADMIN_PASSWORD = 'admin123'`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\mvnw:189` [password] → `case "${MVNW_PASSWORD:+has-password}" in`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\resources\application.properties:17` [password] → `spring.datasource.password=1234`

## המלצת ZIP

**להוציא תמיד מה-ZIP:** `.git`, `.gradle`, `.idea`, `.mvn`, `.next`, `.pytest_cache`, `.venv`, `.vscode`, `__pycache__`, `bin`, `build`, `checkpoints`, `coverage`, `datasets`, `dist`, `node_modules`, `obj`, `out`, `target`, `venv`

**תיקיות גדולות שזוהו (שווה לשקול הוצאה):**
- `ai_engine` — 26.1MB
- `client` — 270.2KB
- `shared_data` — 250.1KB
- `research` — 64.4KB
- `server` — 55.2KB
- `test.py` — 20.7KB
- `diag_2_code.py` — 17.1KB
- `CLAUDE.md` — 14.8KB
- `diag_1_schema.py` — 10.4KB
- `start_all.bat` — 1.1KB

ראי גם את הפקודות המוכנות בסוף ה-README שנלווה לסקריפט הזה, ליצירת ZIP נקי עם robocopy + Compress-Archive.