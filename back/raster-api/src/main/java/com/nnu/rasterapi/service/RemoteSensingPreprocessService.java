package com.nnu.rasterapi.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RemoteSensingPreprocessService {
    public Map<String, Object> plan(String message, String place, String startDate, String endDate) {
        List<Map<String, Object>> steps = List.of(
                step("cloud_mask", "云掩膜", "对 Sentinel-2 QA/SCL 云、云影和无效像元进行掩膜。"),
                step("clip", "裁剪", "按行政区边界或用户自定义框裁剪研究区。"),
                step("resample", "重采样", "统一到 10m 或用户指定分辨率。"),
                step("reproject", "重投影", "统一到 EPSG:4326 或目标投影坐标系。"),
                step("cog", "COG 转换", "输出可被 TiTiler 直接切片的 Cloud Optimized GeoTIFF。")
        );
        List<String> commands = List.of(
                "gdalwarp -cutline boundary.geojson -crop_to_cutline -t_srs EPSG:4326 -tr 10 10 input.tif clipped.tif",
                "gdal_translate -of COG -co COMPRESS=DEFLATE -co BIGTIFF=IF_SAFER clipped.tif output_cog.tif"
        );

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("preprocess_steps", steps);
        extra.put("gdal_commands", commands);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("analysisType", "preprocess");
        out.put("sourceKind", "local-cog");
        out.put("metrics", Map.of("step_count", steps.size()));
        out.put("extra", extra);
        out.put("reportTitle", "遥感影像基础预处理方案");
        out.put("reportSummary", "## 遥感影像基础预处理方案\n\n已生成云掩膜、裁剪、重采样、重投影和 COG 转换的标准处理链路，可作为 gdal-mcp / raster-mcp 的基础能力。");
        out.put("message", "遥感影像基础预处理链路生成完成");
        return out;
    }

    private static Map<String, Object> step(String key, String name, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", key);
        step.put("name", name);
        step.put("description", description);
        return step;
    }
}
