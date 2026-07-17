package com.example.agenticanalytics.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class HealthController {

    public record HealthResponse(String status, Instant timestamp) {}

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP", Instant.now());
    }
}
