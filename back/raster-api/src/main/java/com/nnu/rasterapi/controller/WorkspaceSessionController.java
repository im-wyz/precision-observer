package com.nnu.rasterapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nnu.rasterapi.entity.WorkspaceSession;
import com.nnu.rasterapi.service.WorkspaceSessionService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace-sessions")
@CrossOrigin(origins = "*")
public class WorkspaceSessionController {

    private final WorkspaceSessionService service;
    private final ObjectMapper objectMapper;

    public WorkspaceSessionController(WorkspaceSessionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<WorkspaceSession> rows = service.listRecent();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (WorkspaceSession s : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
            sessions.add(m);
        }
        return Map.of("sessions", sessions);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        WorkspaceSession s = service.get(id);
        return toDto(s);
    }

    @PostMapping
    public Map<String, String> create(@RequestBody JsonNode body) {
        String title = textField(body, "title");
        String stateJson = requireStateJson(body);
        WorkspaceSession s = service.create(title, stateJson);
        return Map.of("id", s.getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body) {
        String title = textField(body, "title");
        String stateJson = requireStateJson(body);
        WorkspaceSession s = service.update(id, title, stateJson);
        return toDto(s);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("ok", true, "id", id);
    }

    private static String textField(JsonNode body, String name) {
        if (body == null || !body.has(name) || body.get(name).isNull()) {
            return "";
        }
        JsonNode n = body.get(name);
        return n.isTextual() ? n.asText() : n.toString();
    }

    private String requireStateJson(JsonNode body) {
        if (body == null || !body.has("state")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "缺少 state 字段");
        }
        JsonNode state = body.get("state");
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "state 无法序列化");
        }
    }

    private Map<String, Object> toDto(WorkspaceSession s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        try {
            m.put("state", objectMapper.readValue(s.getStateJson(), Object.class));
        } catch (Exception e) {
            m.put("state", Map.of("parseError", e.getMessage() != null ? e.getMessage() : "parse failed"));
        }
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        return m;
    }
}