package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.AgentChatService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 聊天代理接口。
 * <p>
 * 接收自然语言指令，交给 AgentChatService 识别意图并返回：
 * 1) 自然语言回答文本
 * 2) ECharts 可直接渲染的 option 配置
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentChatController {
    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    /**
     * 聊天入口：POST /api/agent/chat
     */
    @PostMapping("/chat")
    public AgentChatService.AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        String message = request == null ? null : request.message();
        return agentChatService.handle(message);
    }

    /**
     * 聊天请求体。
     */
    public record AgentChatRequest(String message) {
    }
}

