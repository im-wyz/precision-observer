package com.nnu.rasterapi.service;

import com.nnu.rasterapi.entity.WorkspaceSession;
import com.nnu.rasterapi.repository.WorkspaceSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceSessionService {

    private static final int MAX_STATE_BYTES = 48 * 1024 * 1024;

    private final WorkspaceSessionRepository repository;

    public WorkspaceSessionService(WorkspaceSessionRepository repository) {
        this.repository = repository;
    }

    public List<WorkspaceSession> listRecent() {
        return repository.findRecent(PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    public WorkspaceSession get(String id) {
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不能为空");
        }
        return repository.findById(id.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    @Transactional
    public WorkspaceSession create(String title, String stateJson) {
        validateState(stateJson);
        String t = normalizeTitle(title);
        WorkspaceSession s = new WorkspaceSession();
        s.setId(UUID.randomUUID().toString());
        s.setTitle(t);
        s.setStateJson(stateJson);
        return repository.save(s);
    }

    @Transactional
    public WorkspaceSession update(String id, String title, String stateJson) {
        validateState(stateJson);
        WorkspaceSession s = get(id);
        s.setTitle(normalizeTitle(title));
        s.setStateJson(stateJson);
        return repository.save(s);
    }

    @Transactional
    public void delete(String id) {
        WorkspaceSession s = get(id);
        repository.delete(s);
    }

    private static void validateState(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state 不能为空");
        }
        int bytes = stateJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_STATE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "会话数据过大（" + bytes + " bytes），请缩小对话或结果后再保存");
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未命名工作区";
        }
        String t = title.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
