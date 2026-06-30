# SpeakKey — דוח חילוץ נתונים מקוד Java לפרק 21
נוצר אוטומטית ב-2026-06-22 09:08 | תיקיית סריקה: `C:\Users\WIN 11\Documents\SpeakerAuth`

## ✅ קבצי Java שנמצאו (10):
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\AccessLogController.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\AudioController.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\UserController.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\DemoApplication.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\AccessLog.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\RoomPermission.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\User.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\AccessLogRepository.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\RoomPermissionRepository.java`
- `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\UserRepository.java`


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\AccessLogController.java`

**Package:** `com.example.server.controllers`


### 🏛️ Class: `AccessLogController`  `@RestController` `@RequestMapping` `@CrossOrigin`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Autowired` `AccessLogRepository repository`

**רשימת מתודות:** `getAllLogs()`

#### 🔑 מתודה קריטית: `AccessLogController.getAllLogs`  `@GetMapping`
```java
    public List<AccessLog> getAllLogs() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "timestamp"));
    }
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`AccessLogController.java` (7 נמצאו):
```
L    3 [AccessLog]  import com.example.server.entities.AccessLog;
L    4 [AccessLog]  import com.example.server.repositories.AccessLogRepository;
L   13 [AccessLog]  public class AccessLogController {
L   15 [@Autowired]  @Autowired
L   16 [AccessLog]  private AccessLogRepository repository;
L   19 [@GetMapping]  @GetMapping
L   20 [AccessLog]  public List<AccessLog> getAllLogs() {
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\AudioController.java`

**Package:** `com.example.server.controllers`


### 🏛️ Class: `AudioController`  `@RestController` `@RequestMapping` `@CrossOrigin`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Autowired` `AccessLogRepository accessLogRepository`
- `@Autowired` `UserRepository userRepository`
- `@Autowired` `RoomPermissionRepository roomPermissionRepository`
- `@Autowired` `ObjectMapper objectMapper`
- `@Value` `String aiServerUrl`

**רשימת מתודות:** `uploadFile(@RequestParam MultipartFile file, @RequestParam int roomId)`, `buildError(String reason)`

#### 🔑 מתודה קריטית: `AudioController.uploadFile`  `@PostMapping`
```java
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file")   MultipartFile file,
            @RequestParam("roomId") int           roomId) {

        try {
            System.out.println("\n📡 [Gateway] Access request for room " + roomId);

            // ── Step 1: Collect all enrolled embeddings from MySQL ────────────
            Map<String, Object> embeddingsMap = new HashMap<>();
            for (User u : userRepository.findAll()) {
                String raw = u.getBiometricEmbedding();
                if (raw == null || raw.isBlank()) continue;
                try {
                    List<Double> emb = objectMapper.readValue(
                            raw, new TypeReference<List<Double>>(){});
                    embeddingsMap.put(u.getUsername(), emb);
                } catch (Exception e) {
                    System.err.println("⚠️ [Gateway] Could not parse embedding for user: "
                            + u.getUsername() + " — " + e.getMessage());
                }
            }
            String embeddingsJson = objectMapper.writeValueAsString(embeddingsMap);
            System.out.println("📦 [Gateway] Sending " + embeddingsMap.size()
                    + " enrolled embedding(s) to FastAPI.");

            // ── Step 2: Build multipart request (file + embeddings) ───────────
            ByteArrayResource audioResource = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() {
                    return file.getOriginalFilename() != null
                            ? file.getOriginalFilename() : "audio.wav";
                }
            };

            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file",       new HttpEntity<>(audioResource, fileHeaders));
            body.add("embeddings", embeddingsJson);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            RestTemplate restTemplate = new RestTemplate();

            // ── Step 3: Call FastAPI /predict ─────────────────────────────────
            long t0 = System.currentTimeMillis();
            ResponseEntity<Map> aiResponse =
                    restTemplate.postForEntity(aiServerUrl, request, Map.class);
            System.out.println("⚡ [AI] Analysis done in "
                    + (System.currentTimeMillis() - t0) + "ms");

            if (aiResponse.getStatusCode() != HttpStatus.OK || aiResponse.getBody() == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(buildError("AI server returned no valid response"));
            }

            // ── Step 4: Parse AI response ─────────────────────────────────────
            Map<String, Object> aiBody  = aiResponse.getBody();
            Object matchedRaw           = aiBody.get("matched_username");
            String detectedName         = (matchedRaw != null) ? matchedRaw.toString() : null;
            double confidence           = Double.parseDouble(aiBody.get("confidence").toString());
            // confidence is already 0.0–1.0 — no division needed

            // ── Step 5: Business logic ────────────────────────────────────────
            boolean isApproved      = false;
            String  rejectionReason = "";

            if (detectedName == null) {
    // ... [קוצר — עוד שורות בקובץ המקורי] ...
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`AudioController.java` (31 נמצאו):
```
L    3 [AccessLog]  import com.example.server.entities.AccessLog;
L    5 [AccessLog]  import com.example.server.repositories.AccessLogRepository;
L    6 [RoomPermission]  import com.example.server.repositories.RoomPermissionRepository;
L   18 [MultipartFile]  import org.springframework.web.multipart.MultipartFile;
L   30 [AccessLog]  @Autowired private AccessLogRepository      accessLogRepository;
L   31 [@Autowired]  @Autowired private UserRepository           userRepository;
L   32 [RoomPermission]  @Autowired private RoomPermissionRepository roomPermissionRepository;
L   33 [@Autowired]  @Autowired private ObjectMapper             objectMapper;
L   39 [@PostMapping]  @PostMapping("/upload")
L   40 [ResponseEntity]  public ResponseEntity<Map<String, Object>> uploadFile(
L   41 [MultipartFile]  @RequestParam("file")   MultipartFile file,
L   42 [RequestParam]  @RequestParam("roomId") int           roomId) {
L   88 [ResponseEntity]  ResponseEntity<Map> aiResponse =
L   94 [ResponseEntity]  return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
L  102 [confidence]  double confidence           = Double.parseDouble(aiBody.get("confidence").toString());
L  103 [confidence]  // confidence is already 0.0–1.0 — no division needed
L  112 [confidence]  } else if (confidence < 0.60) {
L  113 [confidence]  rejectionReason = "Biometric confidence too low ("
L  114 [confidence]  + String.format("%.1f", confidence * 100) + "%)";
L  122 [isAuthorized]  } else if (!user.isAuthorized()) {
L  126 [RoomPermission]  boolean hasAccess = roomPermissionRepository
L  136 [AccessLog]  // ── Step 6: Persist AccessLog ─────────────────────────────────────
L  140 [AccessLog]  accessLogRepository.save(new AccessLog(
L  143 [confidence]  confidence,       // 0.0–1.0 stored in DB
L  149 [confidence]  // Send confidence as 0.0–1.0; React multiplies by 100 for display in all pages
L  153 [confidence]  result.put("confidence",        confidence);
L  164 [ResponseEntity]  return ResponseEntity.ok(result);
L  171 [AccessLog]  accessLogRepository.save(new AccessLog(logFilename, null, 0.0, false, roomId));
L  173 [AccessLog]  System.err.println("⚠️ [Gateway] Could not save error AccessLog: " + logEx.getMessage());
L  175 [ResponseEntity]  return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
L  184 [confidence]  err.put("confidence",        0.0);
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\controllers\UserController.java`

**Package:** `com.example.server.controllers`


### 🏛️ Class: `UserController`  `@RestController` `@RequestMapping` `@CrossOrigin`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Autowired` `UserRepository userRepository`
- `@Autowired` `RoomPermissionRepository roomPermissionRepository`
- `@Autowired` `ObjectMapper objectMapper`
- `@Value` `String aiEnrollUrl`
- `@Value` `String uploadDirectory`

**רשימת מתודות:** `getAllUsers()`, `toggleAuthorization(@PathVariable Long id)`, `getUserRooms(@PathVariable String username)`, `toggleUserRoom(@PathVariable String username, @PathVariable int roomNum)`, `registerUserAndEnroll(@RequestParam String username, @RequestParam MultipartFile samples)`

#### 🔑 מתודה קריטית: `UserController.getAllUsers`  `@GetMapping`
```java
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
```

#### 🔑 מתודה קריטית: `UserController.toggleAuthorization`  `@Transactional` `@PutMapping`
```java
    public User toggleAuthorization(@PathVariable Long id) {
        User user = userRepository.findById(id).orElseThrow();
        user.setAuthorized(!user.isAuthorized());
        return userRepository.save(user);
    }
```

#### 🔑 מתודה קריטית: `UserController.getUserRooms`  `@GetMapping`
```java
    public List<Integer> getUserRooms(@PathVariable String username) {
        return roomPermissionRepository
                .findByUsername(username)
                .stream()
                .map(RoomPermission::getRoomNumber)
                .collect(Collectors.toList());
    }
```

#### 🔑 מתודה קריטית: `UserController.toggleUserRoom`  `@Transactional` `@PostMapping`
```java
    public ResponseEntity<List<Integer>> toggleUserRoom(
            @PathVariable String username,
            @PathVariable int    roomNum) {

        if (roomPermissionRepository.existsByUsernameAndRoomNumber(username, roomNum)) {
            roomPermissionRepository.deleteByUsernameAndRoomNumber(username, roomNum);
        } else {
            roomPermissionRepository.save(new RoomPermission(username, roomNum));
        }

        List<Integer> updatedRooms = roomPermissionRepository
                .findByUsername(username)
                .stream()
                .map(RoomPermission::getRoomNumber)
                .collect(Collectors.toList());

        return ResponseEntity.ok(updatedRooms);
    }
```

#### 🔑 מתודה קריטית: `UserController.registerUserAndEnroll`  `@PostMapping`
```java
    public ResponseEntity<?> registerUserAndEnroll(
            @RequestParam("username") String          username,
            @RequestParam("samples")  MultipartFile[] samples) {

        if (samples == null || samples.length < 3) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "נדרשות לפחות שלוש הקלטות קוליות לרישום ביומטרי."));
        }

        try {
            System.out.println("\n🆕 [Gateway] Registering user: " + username
                    + " (" + samples.length + " samples)");

            // ── Back up audio files to disk ───────────────────────────────
            String safeUsername = username.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path userDir = Paths.get(uploadDirectory, safeUsername);
            if (!Files.exists(userDir)) Files.createDirectories(userDir);
            for (MultipartFile sample : samples) {
                if (sample.getOriginalFilename() != null) {
                    String safeFilename = Paths.get(sample.getOriginalFilename()).getFileName().toString();
                    Files.write(userDir.resolve(safeFilename), sample.getBytes());
                }
            }

            // ── Call FastAPI /enroll once per audio sample ────────────────
            // FastAPI accepts one file per call and returns one 192-dim embedding.
            // Java averages all returned embeddings and L2-normalizes before saving.
            System.out.println("📡 [Gateway] Sending " + samples.length
                    + " samples to AI for enrollment...");

            List<List<Double>> allEmbeddings = new ArrayList<>();
            for (int i = 0; i < samples.length; i++) {
                MultipartFile sample = samples[i];
                try {
                    ByteArrayResource res = new ByteArrayResource(sample.getBytes()) {
                        @Override public String getFilename() {
                            return sample.getOriginalFilename() != null
                                    ? sample.getOriginalFilename() : "sample.wav";
                        }
                    };

                    HttpHeaders fileHdr = new HttpHeaders();
                    fileHdr.setContentType(MediaType.APPLICATION_OCTET_STREAM);

                    MultiValueMap<String, Object> enrollBody = new LinkedMultiValueMap<>();
                    enrollBody.add("file", new HttpEntity<>(res, fileHdr));

                    HttpHeaders enrollHdr = new HttpHeaders();
                    enrollHdr.setContentType(MediaType.MULTIPART_FORM_DATA);

                    ResponseEntity<Map> aiResp = new RestTemplate().postForEntity(
                            aiEnrollUrl,
                            new HttpEntity<>(enrollBody, enrollHdr),
                            Map.class);

                    if (aiResp.getStatusCode() == HttpStatus.OK && aiResp.getBody() != null) {
                        Object rawEmb = aiResp.getBody().get("embedding");
                        if (rawEmb != null) {
                            List<Double> emb = objectMapper.convertValue(
                                    rawEmb, new TypeReference<List<Double>>(){});
                            if (emb.size() != 192) {
                                System.err.println("   ⚠️ [Gateway] Sample " + (i + 1)
                                        + " returned embedding of size " + emb.size()
                                        + ", expected 192 — skipped.");
                            } else {
                                allEmbeddings.add(emb);
                                System.out.println("   ✓ Sample " + (i + 1) + "/" + samples.length);
                            }
                        }
                    }
    // ... [קוצר — עוד שורות בקובץ המקורי] ...
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`UserController.java` (31 נמצאו):
```
L    3 [RoomPermission]  import com.example.server.entities.RoomPermission;
L    5 [RoomPermission]  import com.example.server.repositories.RoomPermissionRepository;
L   18 [MultipartFile]  import org.springframework.web.multipart.MultipartFile;
L   33 [@Autowired]  @Autowired private UserRepository           userRepository;
L   34 [RoomPermission]  @Autowired private RoomPermissionRepository roomPermissionRepository;
L   35 [@Autowired]  @Autowired private ObjectMapper             objectMapper;
L   44 [@GetMapping]  @GetMapping
L   51 [@PutMapping]  @PutMapping("/{id}/toggle")
L   54 [isAuthorized]  user.setAuthorized(!user.isAuthorized());
L   59 [@GetMapping]  @GetMapping("/{username}/rooms")
L   61 [RoomPermission]  return roomPermissionRepository
L   64 [RoomPermission]  .map(RoomPermission::getRoomNumber)
L   70 [@PostMapping]  @PostMapping("/{username}/rooms/{roomNum}")
L   71 [ResponseEntity]  public ResponseEntity<List<Integer>> toggleUserRoom(
L   75 [RoomPermission]  if (roomPermissionRepository.existsByUsernameAndRoomNumber(username, roomNum)) {
L   76 [RoomPermission]  roomPermissionRepository.deleteByUsernameAndRoomNumber(username, roomNum);
L   78 [RoomPermission]  roomPermissionRepository.save(new RoomPermission(username, roomNum));
L   81 [RoomPermission]  List<Integer> updatedRooms = roomPermissionRepository
L   84 [RoomPermission]  .map(RoomPermission::getRoomNumber)
L   87 [ResponseEntity]  return ResponseEntity.ok(updatedRooms);
L   93 [@PostMapping]  @PostMapping("/register")
L   94 [ResponseEntity]  public ResponseEntity<?> registerUserAndEnroll(
L   95 [RequestParam]  @RequestParam("username") String          username,
L   96 [MultipartFile]  @RequestParam("samples")  MultipartFile[] samples) {
L   99 [ResponseEntity]  return ResponseEntity.badRequest().body(
L  111 [MultipartFile]  for (MultipartFile sample : samples) {
L  126 [MultipartFile]  MultipartFile sample = samples[i];
L  144 [ResponseEntity]  ResponseEntity<Map> aiResp = new RestTemplate().postForEntity(
L  171 [ResponseEntity]  return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
L  210 [ResponseEntity]  return ResponseEntity.ok(Map.of(
L  218 [ResponseEntity]  return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\DemoApplication.java`

**Package:** `com.example.server`


### 🏛️ Class: `DemoApplication`  `@SpringBootApplication`

**שדות:**
- `@Value` `String uploadDir`

**רשימת מתודות:** `main(String args)`, `initDatabase(UserRepository userRepository)`


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\AccessLog.java`

**Package:** `com.example.server.entities`


### 🏛️ Class: `AccessLog`  `@Entity` `@Table`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Id` `@GeneratedValue` `Long id`
- `@Column` `String filename`
- `@Column` `LocalDateTime timestamp`
- `@Column` `String identifiedSpeaker`
- `@Column` `double confidence`
- `@Column` `boolean accessGranted`
- `@Column` `int roomNumber`
- `@Column` `String ipAddress`
- `@Column` `Long processingTimeMs`
- `@Column` `String rejectionReason`

**רשימת מתודות:** `AccessLog()`, `AccessLog(String filename, String identifiedSpeaker, double confidence, boolean accessGranted, int roomNumber)`, `getId()`, `getFilename()`, `getTimestamp()`, `getIdentifiedSpeaker()`, `getConfidence()`, `isAccessGranted()`, `getRoomNumber()`, `getIpAddress()`, `getProcessingTimeMs()`, `getRejectionReason()`

#### 🔑 מתודה קריטית: `AccessLog.getConfidence`
```java
    public double getConfidence()        { return confidence; }
```

#### 🔑 מתודה קריטית: `AccessLog.isAccessGranted`
```java
    public boolean isAccessGranted()     { return accessGranted; }
```

#### 🔑 מתודה קריטית: `AccessLog.getRoomNumber`
```java
    public int getRoomNumber()           { return roomNumber; }
```

#### 🔑 מתודה קריטית: `AccessLog.getProcessingTimeMs`
```java
    public Long getProcessingTimeMs()    { return processingTimeMs; }
```

#### 🔧 קונסטרוקטור: `AccessLog()`
```java
    public AccessLog() {}
```

#### 🔧 קונסטרוקטור: `AccessLog(String filename, String identifiedSpeaker, double confidence, boolean accessGranted, int roomNumber)`
```java
    public AccessLog(String filename, String identifiedSpeaker,
                     double confidence, boolean accessGranted, int roomNumber) {
        this.filename          = filename;
        this.identifiedSpeaker = identifiedSpeaker;
        this.confidence        = confidence;
        this.accessGranted     = accessGranted;
        this.roomNumber        = roomNumber;
        this.timestamp         = LocalDateTime.now();
    }
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`AccessLog.java` (8 נמצאו):
```
L    6 [@Entity]  @Entity
L    8 [AccessLog]  public class AccessLog {
L   24 [confidence]  private double confidence;
L   42 [AccessLog]  public AccessLog() {}
L   45 [AccessLog]  public AccessLog(String filename, String identifiedSpeaker,
L   46 [confidence]  double confidence, boolean accessGranted, int roomNumber) {
L   49 [confidence]  this.confidence        = confidence;
L   60 [confidence]  public double getConfidence()        { return confidence; }
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\RoomPermission.java`

**Package:** `com.example.server.entities`


### 🏛️ Class: `RoomPermission`  `@Entity` `@Table`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Id` `@GeneratedValue` `Long id`
-  `String username`
-  `int roomNumber`

**רשימת מתודות:** `RoomPermission()`, `RoomPermission(String username, int roomNumber)`, `getUsername()`, `getRoomNumber()`

#### 🔑 מתודה קריטית: `RoomPermission.getRoomNumber`
```java
    public int getRoomNumber() { return roomNumber; }
```

#### 🔧 קונסטרוקטור: `RoomPermission()`
```java
    public RoomPermission() {}
```

#### 🔧 קונסטרוקטור: `RoomPermission(String username, int roomNumber)`
```java
    public RoomPermission(String username, int roomNumber) {
        this.username = username;
        this.roomNumber = roomNumber;
    }
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`RoomPermission.java` (4 נמצאו):
```
L    5 [@Entity]  @Entity
L    7 [RoomPermission]  public class RoomPermission {
L   17 [RoomPermission]  public RoomPermission() {}
L   20 [RoomPermission]  public RoomPermission(String username, int roomNumber) {
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\entities\User.java`

**Package:** `com.example.server.entities`


### 🏛️ Class: `User`  `@Entity` `@Table`
**⭐ מחלקה מבנית (Controller/Service/Entity/Repository/Config) — רלוונטית מאוד לפרק 21**

**שדות:**
- `@Id` `@GeneratedValue` `Long id`
- `@Column` `String username`
- `@Column` `String fullName`
- `@Column` `boolean isAuthorized`
- `@Column` `LocalDateTime createdAt`
- `@JsonIgnore` `@Column` `String biometricEmbedding`

**רשימת מתודות:** `User()`, `User(String username, String fullName, boolean isAuthorized)`, `getId()`, `getUsername()`, `getFullName()`, `isAuthorized()`, `getCreatedAt()`, `getBiometricEmbedding()`, `setUsername(String username)`, `setFullName(String fullName)`, `setAuthorized(boolean authorized)`, `setBiometricEmbedding(String emb)`

#### 🔑 מתודה קריטית: `User.isAuthorized`
```java
    public boolean isAuthorized()         { return isAuthorized; }
```

#### 🔑 מתודה קריטית: `User.setAuthorized`
```java
    public void setAuthorized(boolean authorized)    { this.isAuthorized = authorized; }
```

#### 🔧 קונסטרוקטור: `User()`
```java
    public User() {}
```

#### 🔧 קונסטרוקטור: `User(String username, String fullName, boolean isAuthorized)`
```java
    public User(String username, String fullName, boolean isAuthorized) {
        this.username           = username;
        this.fullName           = fullName;
        this.isAuthorized       = isAuthorized;
        this.createdAt          = LocalDateTime.now();
        this.biometricEmbedding = null;   // נכתב לאחר /enroll
    }
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`User.java` (6 נמצאו):
```
L   18 [@Entity]  @Entity
L   33 [isAuthorized]  private boolean isAuthorized;
L   56 [isAuthorized]  public User(String username, String fullName, boolean isAuthorized) {
L   59 [isAuthorized]  this.isAuthorized       = isAuthorized;
L   68 [isAuthorized]  public boolean isAuthorized()         { return isAuthorized; }
L   75 [isAuthorized]  public void setAuthorized(boolean authorized)    { this.isAuthorized = authorized; }
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\AccessLogRepository.java`

**Package:** `com.example.server.repositories`


### 🏛️ Interface: `AccessLogRepository`  (יורש/ת מ-`JpaRepository`)

### 🔎 שורות רלוונטיות לקלט/פלט ב-`AccessLogRepository.java` (3 נמצאו):
```
L    3 [AccessLog]  import com.example.server.entities.AccessLog;
L    4 [JpaRepository]  import org.springframework.data.jpa.repository.JpaRepository;
L    6 [AccessLog]  public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\RoomPermissionRepository.java`

**Package:** `com.example.server.repositories`


### 🏛️ Interface: `RoomPermissionRepository`  (יורש/ת מ-`JpaRepository`)

**רשימת מתודות:** `existsByUsernameAndRoomNumber(String username, int roomNumber)`, `findByUsername(String username)`, `deleteByUsernameAndRoomNumber(String username, int roomNumber)`

#### 🔑 מתודה קריטית: `RoomPermissionRepository.existsByUsernameAndRoomNumber`
```java
    boolean existsByUsernameAndRoomNumber(String username, int roomNumber);

    // חדש — שליפת כל החדרים של משתמש
    List<RoomPermission> findByUsername(String username);

    // חדש — מחיקת הרשאה ספציפית (חייב @Transactional לפעולת מחיקה derived)
    @Transactional
    void deleteByUsernameAndRoomNumber(String username, int roomNumber);
}
```

#### 🔑 מתודה קריטית: `RoomPermissionRepository.findByUsername`
```java
    List<RoomPermission> findByUsername(String username);

    // חדש — מחיקת הרשאה ספציפית (חייב @Transactional לפעולת מחיקה derived)
    @Transactional
    void deleteByUsernameAndRoomNumber(String username, int roomNumber);
}
```

#### 🔑 מתודה קריטית: `RoomPermissionRepository.deleteByUsernameAndRoomNumber`  `@Transactional`
```java
    void deleteByUsernameAndRoomNumber(String username, int roomNumber);
}
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`RoomPermissionRepository.java` (4 נמצאו):
```
L    3 [RoomPermission]  import com.example.server.entities.RoomPermission;
L    4 [JpaRepository]  import org.springframework.data.jpa.repository.JpaRepository;
L    9 [RoomPermission]  public interface RoomPermissionRepository extends JpaRepository<RoomPermission, Long> {
L   15 [RoomPermission]  List<RoomPermission> findByUsername(String username);
```


## 📄 קובץ: `C:\Users\WIN 11\Documents\SpeakerAuth\server\src\main\java\com\example\server\repositories\UserRepository.java`

**Package:** `com.example.server.repositories`


### 🏛️ Interface: `UserRepository`  (יורש/ת מ-`JpaRepository`)

**רשימת מתודות:** `findByUsername(String username)`

#### 🔑 מתודה קריטית: `UserRepository.findByUsername`
```java
    User findByUsername(String username);
}
```

### 🔎 שורות רלוונטיות לקלט/פלט ב-`UserRepository.java` (2 נמצאו):
```
L    4 [JpaRepository]  import org.springframework.data.jpa.repository.JpaRepository;
L    6 [JpaRepository]  public interface UserRepository extends JpaRepository<User, Long> {
```