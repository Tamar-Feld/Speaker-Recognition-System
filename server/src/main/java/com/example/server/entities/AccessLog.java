package com.example.server.entities;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "access_logs")
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "identified_speaker", nullable = true, length = 100)
    private String identifiedSpeaker;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "access_granted", nullable = false)
    private boolean accessGranted;

    @Column(name = "room_number", nullable = false)
    private int roomNumber;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** חובה ל-JPA */
    public AccessLog() {}

    /** Constructor ראשי */
    public AccessLog(String filename, String identifiedSpeaker,
                     double confidence, boolean accessGranted, int roomNumber) {
        this.filename          = filename;
        this.identifiedSpeaker = identifiedSpeaker;
        this.confidence        = confidence;
        this.accessGranted     = accessGranted;
        this.roomNumber        = roomNumber;
        this.timestamp         = LocalDateTime.now();
    }
    /** Constructor מורחב — כולל IP, זמן עיבוד וסיבת דחייה פנימית (M-5) */
    public AccessLog(String filename, String identifiedSpeaker,
                     double confidence, boolean accessGranted, int roomNumber,
                     String ipAddress, Long processingTimeMs, String rejectionReason) {
        this(filename, identifiedSpeaker, confidence, accessGranted, roomNumber);
        this.ipAddress = ipAddress;
        this.processingTimeMs = processingTimeMs;
        this.rejectionReason = rejectionReason;
    }
    // Getters
    public Long getId()                  { return id; }
    public String getFilename()          { return filename; }
    public LocalDateTime getTimestamp()  { return timestamp; }
    public String getIdentifiedSpeaker() { return identifiedSpeaker; }
    public double getConfidence()        { return confidence; }
    public boolean isAccessGranted()     { return accessGranted; }
    public int getRoomNumber()           { return roomNumber; }
    public String getIpAddress()         { return ipAddress; }
    public Long getProcessingTimeMs()    { return processingTimeMs; }
    public String getRejectionReason()   { return rejectionReason; }
}