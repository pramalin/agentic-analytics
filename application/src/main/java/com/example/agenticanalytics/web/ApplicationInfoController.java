package com.example.agenticanalytics.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationInfoController {

    public record InfoResponse(String name, String description) {}

    @Value("${spring.application.name}")
    private String applicationName;

    @GetMapping("/api/info")
    public InfoResponse info() {
        return new InfoResponse(applicationName,
                "Agentic analytics backend — data mart, Spring AI agent, MCP tools");
    }
}
