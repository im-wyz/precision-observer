package com.nnu.rasterapi.redis;

import com.nnu.rasterapi.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订阅 Redis 模式 {@code task:*}，将 Python 发布的 JSON 经 STOMP 转发到 {@code /topic/task/{taskId}}。
 */
@Component
public class RedisMessageSubscriber implements MessageListener {

    private final TaskService taskService;
    private final String channelPrefix;

    public RedisMessageSubscriber(
            TaskService taskService,
            @Value("${agent.redis-pubsub-channel-prefix:task:}") String channelPrefix
    ) {
        this.taskService = taskService;
        this.channelPrefix = channelPrefix == null ? "task:" : channelPrefix;
    }

    @Override
    public void onMessage(Message message, @Nullable byte[] pattern) {
        if (message == null || message.getChannel() == null || message.getBody() == null) {
            return;
        }
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String taskId = extractTaskId(channel);
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskService.onRedisProgress(taskId, body);
    }

    private String extractTaskId(String channel) {
        if (channel == null) {
            return null;
        }
        if (channel.startsWith(channelPrefix)) {
            return channel.substring(channelPrefix.length());
        }
        int idx = channel.lastIndexOf(':');
        return idx >= 0 && idx < channel.length() - 1 ? channel.substring(idx + 1) : channel;
    }
}
