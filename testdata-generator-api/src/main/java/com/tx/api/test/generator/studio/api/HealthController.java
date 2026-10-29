package com.tx.api.test.generator.studio.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final ObjectProvider<JdbcTemplate> jdbcTemplates;

    public HealthController(ObjectProvider<JdbcTemplate> jdbcTemplates) {
        this.jdbcTemplates = jdbcTemplates;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        JdbcTemplate jdbc = jdbcTemplates.getIfAvailable();
        if (jdbc != null) {
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                body.put("database", "UP");
            } catch (RuntimeException ex) {
                body.put("status", "DOWN");
                body.put("database", "DOWN");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
            }
        }
        return ResponseEntity.ok(body);
    }
}
