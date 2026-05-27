package com.nnu.rasterapi.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nnu.rasterapi.service.RoboflowDiagnosticsService;
import com.nnu.rasterapi.service.RoboflowWorkflowService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地物提取等工作流：前端传 instruction + 上传影像的 cogHttpUrl，后端生成预览并调用 Roboflow。
 */
@RestController
@RequestMapping("/api/roboflow")
@CrossOrigin(origins = "*")
public class RoboflowWorkflowController {

    private final RoboflowWorkflowService roboflowWorkflowService;
    private final RoboflowDiagnosticsService roboflowDiagnosticsService;

    public RoboflowWorkflowController(
            RoboflowWorkflowService roboflowWorkflowService,
            RoboflowDiagnosticsService roboflowDiagnosticsService
    ) {
        this.roboflowWorkflowService = roboflowWorkflowService;
        this.roboflowDiagnosticsService = roboflowDiagnosticsService;
    }

    /**
     * 地物提取链路自检。上传后可将 workflow 请求体里的 cogHttpUrl 作为查询参数传入。
     * 例：GET /api/roboflow/diagnostics?cogHttpUrl=http://223.2.33.91:8080/api/upload/raster/file/xxx.tif
     */
    @GetMapping("/diagnostics")
    public ObjectNode diagnostics(@RequestParam(required = false) String cogHttpUrl) {
        return roboflowDiagnosticsService.diagnose(cogHttpUrl);
    }

    @PostMapping("/workflow")
    public JsonNode workflow(@RequestBody RoboflowWorkflowRequest request) {
        String instruction = request == null ? null : request.instruction();
        String cogHttpUrl = request == null ? null : request.cogHttpUrl();
        String modelId = request == null ? null : request.modelId();
        String inferenceBaseUrl = request == null ? null : request.inferenceBaseUrl();
        String inferenceTask = request == null ? null : request.inferenceTask();
        String workspaceName = request == null ? null : request.workspaceName();
        String workflowId = request == null ? null : request.workflowId();
        Double pixelsPerUnit = request == null ? null : request.pixelsPerUnit();
        return roboflowWorkflowService.runWorkflow(
                instruction,
                cogHttpUrl,
                modelId,
                inferenceBaseUrl,
                inferenceTask,
                workspaceName,
                workflowId,
                pixelsPerUnit);
    }

    public record RoboflowWorkflowRequest(
            String instruction,
            String cogHttpUrl,
            String modelId,
            String inferenceBaseUrl,
            String inferenceTask,
            String workspaceName,
            String workflowId,
            @JsonAlias({"pixels_per_unit"}) Double pixelsPerUnit
    ) {
    }
}
