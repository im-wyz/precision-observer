package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.AgentTaskService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v2 智能体任务 API：前端提交自然语言 → Spring 转发 Python LangGraph → Redis 轮询状态。
 */
@RestController
@RequestMapping("/api/agent/tasks")
@CrossOrigin(origins = "*")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    public AgentTaskController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AgentTaskRequest request) {
        String message = request == null ? null : request.message();
        Map<String, Object> context = request == null ? null : request.context();
        return agentTaskService.submitTask(message, context);
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> get(@PathVariable String taskId) {
        return agentTaskService.getTask(taskId);
    }

    public record AgentTaskRequest(String message, Map<String, Object> context) {
    }
}
