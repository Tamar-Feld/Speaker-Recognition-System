package com.example.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = "*")
public class AccessLogController {

    @Autowired
    private AccessLogRepository repository;

    // שליפת כל הלוגים (מהחדש לישן)
    @GetMapping
    public List<AccessLog> getAllLogs() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "timestamp"));
    }
}