package com.example.server;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String filename;
    private LocalDateTime timestamp;
    private String identifiedSpeaker;
    private String confidence;
    private boolean accessGranted;

    public AccessLog() {}

    public AccessLog(String filename, String identifiedSpeaker, String confidence, boolean accessGranted) {
        this.filename = filename;
        this.identifiedSpeaker = identifiedSpeaker;
        this.confidence = confidence;
        this.accessGranted = accessGranted;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getFilename() { return filename; }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getIdentifiedSpeaker() {
        return identifiedSpeaker;
    }

    public String getConfidence() {
        return confidence;
    }

    public boolean isAccessGranted() {
        return accessGranted;
    }
}