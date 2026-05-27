package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.AiCommandService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-agent")
@CrossOrigin(origins = "*")
public class AiAgentController {
    private final AiCommandService aiCommandService;

    public AiAgentController(AiCommandService aiCommandService) {
        this.aiCommandService = aiCommandService;
    }

    @PostMapping("/execute")
    public AiCommandService.AiCommandResult execute(@RequestBody AiCommandRequest body) {
        String command = body == null ? null : body.command();
        return aiCommandService.execute(command);
    }

    public record AiCommandRequest(String command) {
    }
}

