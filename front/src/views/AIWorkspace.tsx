import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  Bot,
  User,
  Image as ImageIcon,
  RectangleHorizontal,
  Paperclip,
  Send,
  ZoomIn,
  ZoomOut,
  Ruler,
  Rotate3d,
  Settings,
} from 'lucide-react';
import { motion } from 'motion/react';
import * as Cesium from 'cesium';
import {
  ArcGisBaseMapType,
  ArcGisMapServerImageryProvider,
  Cartesian2,
  Cartesian3,
  Color,
  createWorldTerrainAsync,
  defined,
  Entity,
  HeightReference,
  ImageryLayer,
  Ion,
  LabelStyle,
  Math as CesiumMath,
  PolygonHierarchy,
  ProviderViewModel,
  Rectangle,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  SingleTileImageryProvider,
  SplitDirection,
  UrlTemplateImageryProvider,
  Viewer,
  VerticalOrigin,
  WebMercatorTilingScheme,
} from 'cesium';
import * as echarts from 'echarts';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { useAgentV2 } from '../api/agentTasks';
import { MULTI_AGENT_PENDING_KEY, useMultiAgentTaskRunner } from '../hooks/useMultiAgentTaskRunner';
import { applyTaskSnapshotToViewer, buildFinalAnswerFromSnapshot } from '../lib/agentMapApply';
import type { TaskRedisSnapshot } from '../api/tasks';

/** Cesium 运行时从 widgets 再导出，部分 TS 声明未收录 */
function createDefaultImageryProviderViewModels(): ProviderViewModel[] {
  const fn = (Cesium as Record<string, unknown>).createDefaultImageryProviderViewModels;
  if (typeof fn !== 'function') {
    throw new Error('Cesium createDefaultImageryProviderViewModels 不可用');
  }
  return (fn as () => ProviderViewModel[])();
}

type Extent = { minLng: number; minLat: number; maxLng: number; maxLat: number };

/** 本地上传成功后缓存，供 Roboflow 工作流等地物分析使用 */
type LastUploadContext = {
  cogHttpUrl: string;
  extent: Extent;
  tileTemplateUrl: string;
  tileMaxZoom?: number | null;
  tileMinZoom?: number | null;
};

const WORKSPACE_SESSION_STORAGE_KEY = 'po_active_workspace_session_id';

/** 持久化到 PostgreSQL 的工作区快照（版本化便于以后迁移） */
type WorkspacePersistedStateV1 = {
  version: 1;
  messages: ChatMessage[];
  uploadContext: LastUploadContext | null;
  uploadDisplayName: string | null;
  roboflowWorkflowJson: unknown | null;
  croplandRestore: {
    place: string;
    startYear: number;
    startMonth: number;
    endYear: number;
    endMonth: number;
  } | null;
  analysisMode: boolean;
  customImageryMode: boolean;
  splitPercent: number;
};

function stripLargeStringsForSave(value: unknown, maxLen: number): unknown {
  try {
    return JSON.parse(
      JSON.stringify(value, (_k, v) => {
        if (typeof v === 'string' && v.length > maxLen) {
          if (v.startsWith('data:')) return '[omitted-inline-image]';
          return `${v.slice(0, 200)}…(省略 ${v.length} 字符)`;
        }
        return v;
      }),
    );
  } catch {
    return value;
  }
}

function deriveSessionTitle(messages: ChatMessage[], uploadDisplayName: string | null): string {
  if (uploadDisplayName && uploadDisplayName.trim()) {
    return uploadDisplayName.trim().slice(0, 500);
  }
  const firstUser = messages.find((m) => m.role === 'user');
  if (firstUser?.text?.trim()) {
    const t = firstUser.text.trim();
    return (t.length > 120 ? `${t.slice(0, 120)}…` : t).slice(0, 500);
  }
  return `工作区 ${new Date().toLocaleString()}`;
}

function isPersistableWorkspaceState(s: WorkspacePersistedStateV1): boolean {
  const hasUser = s.messages.some((m) => m.role === 'user');
  return (
    hasUser ||
    s.uploadContext != null ||
    s.roboflowWorkflowJson != null ||
    s.croplandRestore != null
  );
}

function coercePersistedState(raw: unknown): WorkspacePersistedStateV1 | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.version !== 1) return null;
  const messages = Array.isArray(o.messages) ? (o.messages as ChatMessage[]) : null;
  if (!messages) return null;
  return {
    version: 1,
    messages,
    uploadContext: (o.uploadContext as LastUploadContext | null) ?? null,
    uploadDisplayName: typeof o.uploadDisplayName === 'string' ? o.uploadDisplayName : null,
    roboflowWorkflowJson: o.roboflowWorkflowJson ?? null,
    croplandRestore:
      o.croplandRestore && typeof o.croplandRestore === 'object'
        ? (o.croplandRestore as WorkspacePersistedStateV1['croplandRestore'])
        : null,
    analysisMode: Boolean(o.analysisMode),
    customImageryMode: Boolean(o.customImageryMode),
    splitPercent: typeof o.splitPercent === 'number' && Number.isFinite(o.splitPercent) ? o.splitPercent : 50,
  };
}

type AIWorkspaceProps = {
  pendingSessionId?: string | null;
  onPendingSessionConsumed?: () => void;
  /** 递增时清空对话与地图并回到空白工作区（先于会话恢复执行） */
  newWorkspaceNonce?: number;
};

/** 自然语言遥感分析 → LangGraph 多智能体（/api/tasks + STOMP） */
function shouldRunMultiAgentAnalysis(text: string): boolean {
  if (looksRoboflowFeatureInstruction(text)) return false;
  if (useAgentV2()) return true;
  return /分析|监测|蓝藻|藻|太湖|巢湖|鄱阳|遥感|NDCI|NDVI|NDWI|NDBI|NBR|SAVI|指数|耕地|农田|面积|水体|水质|变化|变化检测|真彩色|假彩色|农业假彩色|合成|阈值|分割|提取|影像目录|质量检查|覆盖率|预处理|云掩膜|去云|裁剪|行政区|自定义框|重采样|分辨率|重投影|云优化|COG|gdalwarp|gdal_translate|夏季|冬季|春季|秋季/i.test(text);
}

function looksRoboflowFeatureInstruction(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed.length) return false;
  let t = trimmed.toLowerCase();
  try {
    t = t.normalize('NFKC');
  } catch {
    // ignore
  }
  return (
    /\b(buildings?|roads?|waters?)\b/.test(t) ||
    /\b(building|road|water)\b/.test(t) ||
    /建筑|道路|水体|地物|分割|提取/.test(trimmed)
  );
}

const ROBOFLOW_PENDING_MESSAGE =
  '正在调用地物分析（Roboflow），请稍候…首次冷启动或模型加载时可能需 2–5 分钟，请勿重复发送。';
/** 对话里「思考中」占位，由 STOMP 进度不断替换同一条 assistant 消息 */
const MULTI_AGENT_THINKING_PREFIX = '智能体正在协作分析';
/** 略大于后端 TiTiler 多路径预览 + inference-max-wall-ms，避免浏览器先断开而后端仍跑 */
const ROBOFLOW_CLIENT_TIMEOUT_MS = 960_000;
/** 过多矢量实体会长时间阻塞主线程，表现为「分析卡住」 */
const MAX_ROBOFLOW_MAP_ENTITIES = 800;

function findHttpImageInRoboflowJson(root: unknown, depth = 0): string | null {
  if (depth > 14 || root == null) return null;
  if (typeof root === 'string') {
    const s = root.trim();
    if (s.startsWith('data:image/')) return s;
    if (/^https?:\/\//i.test(s)) {
      if (/\.(png|jpe?g|webp|gif)(\?|#|$)/i.test(s)) return s;
      // 工作流/签名 URL 常无后缀：仅对较短链路与常见图床特征放宽，减少误把普通 API 链当图
      if (
        s.length <= 4096 &&
        /(roboflow|inference|amazonaws|cloudfront|blob\.core\.windows\.net|\/files\/|format=png|format=jpe?g|content-type=image)/i.test(
          s,
        )
      ) {
        return s;
      }
    }
    return null;
  }
  if (Array.isArray(root)) {
    for (const x of root) {
      const u = findHttpImageInRoboflowJson(x, depth + 1);
      if (u) return u;
    }
    return null;
  }
  if (typeof root === 'object') {
    for (const v of Object.values(root as Record<string, unknown>)) {
      const u = findHttpImageInRoboflowJson(v, depth + 1);
      if (u) return u;
    }
  }
  return null;
}

function findBase64ImageInRoboflowJson(root: unknown, depth = 0): string | null {
  if (depth > 14 || root == null) return null;
  if (typeof root === 'string') {
    const compact = root.replace(/\s/g, '');
    if (compact.length > 800 && /^[A-Za-z0-9+/=]+$/.test(compact)) return compact;
    return null;
  }
  if (Array.isArray(root)) {
    for (const x of root) {
      const u = findBase64ImageInRoboflowJson(x, depth + 1);
      if (u) return u;
    }
    return null;
  }
  if (typeof root === 'object') {
    for (const v of Object.values(root as Record<string, unknown>)) {
      const u = findBase64ImageInRoboflowJson(v, depth + 1);
      if (u) return u;
    }
  }
  return null;
}

/**
 * 工作流新版多图输出：优先用无标签的干净图做地图叠加（与点击面积交互一致）。
 * 顺序与常见 Roboflow Workflow outputs 字段对齐；未命中再回退到全 JSON 扫描。
 */
const ROBOFLOW_OVERLAY_OUTPUT_KEYS = [
  'polygon_clean',
  'polygon_annotated',
  'mask_clean',
  'mask_annotated',
  'bbox_clean',
  'bbox_annotated',
  'isolated_objects',
  'stats_image',
  'annotated_image',
  'labeled_image',
  'output_image',
  'visualization',
  'visualization_image',
  'segmentation_visualization',
  'mask',
  'image',
] as const;

/** 单步输出节点：{ type, value } 或直接 base64/url 字符串 */
function imageUrlFromWorkflowOutputNode(node: unknown): string | null {
  if (node == null) return null;
  if (typeof node === 'string') {
    const s = node.trim();
    if (/^https?:\/\//i.test(s)) return s;
    if (s.startsWith('data:')) return s;
    const compact = s.replace(/\s/g, '');
    if (compact.length > 200 && /^[A-Za-z0-9+/=]+$/.test(compact)) return `data:image/png;base64,${compact}`;
    return null;
  }
  if (typeof node !== 'object' || Array.isArray(node)) return null;
  const o = node as Record<string, unknown>;
  const rawVal = o.value ?? o.image ?? o.base64 ?? o.data;
  if (typeof rawVal !== 'string') return null;
  const val = rawVal.trim();
  if (/^https?:\/\//i.test(val)) return val;
  if (val.startsWith('data:')) return val;
  const compact = val.replace(/\s/g, '');
  if (compact.length > 200 && /^[A-Za-z0-9+/=]+$/.test(compact)) {
    const t = String(o.type ?? '').toLowerCase();
    const mime =
      t.includes('jpeg') || t.includes('jpg')
        ? 'image/jpeg'
        : t.includes('webp')
          ? 'image/webp'
          : 'image/png';
    return `data:${mime};base64,${compact}`;
  }
  return null;
}

function collectWorkflowOutputMaps(node: unknown, depth: number): Record<string, unknown>[] {
  if (depth > 12 || node == null || typeof node !== 'object') return [];
  if (Array.isArray(node)) return [];
  const o = node as Record<string, unknown>;
  const acc: Record<string, unknown>[] = [];
  const outs = o.outputs;
  if (outs && typeof outs === 'object') {
    if (Array.isArray(outs)) {
      for (const x of outs) {
        if (x && typeof x === 'object' && !Array.isArray(x)) acc.push(x as Record<string, unknown>);
      }
    } else {
      acc.push(outs as Record<string, unknown>);
    }
  }
  for (const v of Object.values(o)) {
    acc.push(...collectWorkflowOutputMaps(v, depth + 1));
  }
  return acc;
}

function overlayUrlFromOutputRecord(rec: Record<string, unknown>): string | null {
  for (const key of ROBOFLOW_OVERLAY_OUTPUT_KEYS) {
    const u = imageUrlFromWorkflowOutputNode(rec[key]);
    if (u) return u;
  }
  return null;
}

/** 从 Roboflow 返回 JSON 中取可给 Cesium 单瓦片叠加的 URL（http(s) 或 data:） */
function extractRoboflowOverlayImageUrl(root: unknown): string | null {
  const maps = collectWorkflowOutputMaps(root, 0);
  for (const m of maps) {
    const u = overlayUrlFromOutputRecord(m);
    if (u) return u;
  }
  const inner = getWorkflowInnerRecord(root);
  if (inner) {
    const u = overlayUrlFromOutputRecord(inner);
    if (u) return u;
  }
  const httpUrl = findHttpImageInRoboflowJson(root);
  if (httpUrl) return httpUrl;
  const b64 = findBase64ImageInRoboflowJson(root);
  if (b64) return `data:image/png;base64,${b64}`;
  return null;
}

type RoboflowBoxPred = { x: number; y: number; width: number; height: number; className?: string };

/** 解析自建 Inference 返回的 predictions（含外层 result 包装） */
function extractRoboflowPredictionsPayload(root: unknown): { predictions: RoboflowBoxPred[]; imgW: number; imgH: number } | null {
  if (!root || typeof root !== 'object') return null;
  const r = root as Record<string, unknown>;
  const inner = (r.result && typeof r.result === 'object' ? r.result : r) as Record<string, unknown>;
  const predsRaw = inner.predictions;
  const predsWithArea = inner.predictions_with_area;
  const preds =
    Array.isArray(predsRaw) && predsRaw.length > 0
      ? predsRaw
      : Array.isArray(predsWithArea) && predsWithArea.length > 0
        ? predsWithArea
        : null;
  if (!preds || preds.length === 0) return null;
  const img = inner.image && typeof inner.image === 'object' ? (inner.image as Record<string, unknown>) : null;
  const imgW = typeof img?.width === 'number' && Number.isFinite(img.width) ? img.width : 640;
  const imgH = typeof img?.height === 'number' && Number.isFinite(img.height) ? img.height : 640;
  const out: RoboflowBoxPred[] = [];
  for (const p of preds) {
    if (!p || typeof p !== 'object') continue;
    const o = p as Record<string, unknown>;
    const x = Number(o.x);
    const y = Number(o.y);
    const w = Number(o.width);
    const h = Number(o.height);
    if (![x, y, w, h].every(Number.isFinite)) continue;
    const cn =
      typeof o.class === 'string'
        ? o.class
        : typeof o.class_name === 'string'
          ? o.class_name
          : undefined;
    out.push({ x, y, width: w, height: h, className: cn });
  }
  return out.length > 0 ? { predictions: out, imgW, imgH } : null;
}

/** 工作流可能在任意嵌套字段里挂 predictions_with_area */
function findWorkflowPredictionsArray(root: unknown): unknown[] | null {
  const inner = getWorkflowInnerRecord(root);
  if (!inner) return null;
  const direct = inner.predictions_with_area ?? inner.predictions;
  if (Array.isArray(direct) && direct.length > 0) return direct;
  return deepFindPredictionsArray(inner, 0);
}

function getWorkflowInnerRecord(root: unknown): Record<string, unknown> | null {
  if (!root || typeof root !== 'object') return null;
  const r = root as Record<string, unknown>;
  return (r.result && typeof r.result === 'object' ? r.result : r) as Record<string, unknown>;
}

function deepFindPredictionsArray(node: unknown, depth: number): unknown[] | null {
  if (depth > 14 || node == null) return null;
  if (typeof node !== 'object') return null;
  if (Array.isArray(node)) return null;
  const o = node as Record<string, unknown>;
  const pa = o.predictions_with_area;
  if (Array.isArray(pa) && pa.length > 0) return pa;
  const pr = o.predictions;
  if (Array.isArray(pr) && pr.length > 0) return pr;
  for (const v of Object.values(o)) {
    const found = deepFindPredictionsArray(v, depth + 1);
    if (found) return found;
  }
  return null;
}

function extractPolygonPixelsFromPrediction(o: Record<string, unknown>): [number, number][] | null {
  const rawPoly = o.polygon ?? o.points ?? o.contour ?? o.polygons ?? o.poly ?? o.shape;
  const poly =
    Array.isArray(rawPoly)
      ? rawPoly
      : rawPoly && typeof rawPoly === 'object' && Array.isArray((rawPoly as Record<string, unknown>).points)
        ? ((rawPoly as Record<string, unknown>).points as unknown[])
        : null;
  if (!Array.isArray(poly) || poly.length < 3) return null;
  const out: [number, number][] = [];
  for (const pt of poly) {
    if (Array.isArray(pt) && pt.length >= 2) {
      const x = Number(pt[0]);
      const y = Number(pt[1]);
      if (Number.isFinite(x) && Number.isFinite(y)) out.push([x, y]);
    } else if (pt && typeof pt === 'object') {
      const rec = pt as Record<string, unknown>;
      const x = Number(rec.x ?? rec[0]);
      const y = Number(rec.y ?? rec[1]);
      if (Number.isFinite(x) && Number.isFinite(y)) out.push([x, y]);
    }
  }
  if (out.length >= 3) {
    const a = out[0];
    const b = out[out.length - 1];
    if (a && b && a[0] === b[0] && a[1] === b[1]) out.pop();
  }
  return out.length >= 3 ? out : null;
}

function formatRoboflowAreaLine(o: Record<string, unknown>): string {
  const cls =
    typeof o.class === 'string'
      ? o.class
      : typeof o.class_name === 'string'
        ? o.class_name
        : typeof o.label === 'string'
          ? o.label
          : undefined;
  const unitRaw = o.area_unit ?? o.unit;
  const unit = typeof unitRaw === 'string' && unitRaw.trim() ? unitRaw.trim() : 'm²';

  const tryNum = (...keys: string[]) => {
    for (const k of keys) {
      const v = o[k];
      if (typeof v === 'number' && Number.isFinite(v)) return v;
      if (typeof v === 'string') {
        const n = Number(String(v).replace(/,/g, ''));
        if (Number.isFinite(n)) return n;
      }
    }
    return null;
  };

  const areaNumM2 = tryNum(
    'area_m2',
    'area_square_meters',
    'area_sqm',
    'area_converted',
    'physical_area',
    'real_world_area',
    'surface_area_m2',
    'geo_area_m2',
    'mask_area_m2',
    'mask_area',
    'area',
  );
  const areaNumPx = tryNum('area_pixels', 'pixel_area', 'mask_area_pixels');
  let mid: string;
  if (areaNumM2 != null) {
    mid = `${areaNumM2 >= 100 ? areaNumM2.toFixed(1) : areaNumM2.toFixed(3)} ${unit}`;
  } else if (areaNumPx != null) {
    mid = `${areaNumPx >= 1000 ? areaNumPx.toFixed(0) : areaNumPx.toFixed(1)} px²`;
  } else if (typeof o.area === 'string' && o.area.trim()) {
    mid = o.area.trim();
  } else if (typeof o.area_display === 'string' && o.area_display.trim()) {
    mid = o.area_display.trim();
  } else {
    mid = '面积未返回';
  }

  return cls ? `${cls} · ${mid}` : mid;
}

type RoboflowPickMeta = { areaText: string; centroid: Cartesian3 };

function findWorkflowTotalCount(root: unknown): number | null {
  function walk(node: unknown, depth: number): number | null {
    if (depth > 14 || node == null || typeof node !== 'object') return null;
    if (Array.isArray(node)) {
      for (const x of node) {
        const f = walk(x, depth + 1);
        if (f != null) return f;
      }
      return null;
    }
    const o = node as Record<string, unknown>;
    const tc = o.total_count;
    if (typeof tc === 'number' && Number.isFinite(tc)) return tc;
    if (typeof tc === 'string') {
      const n = Number(String(tc).replace(/,/g, ''));
      if (Number.isFinite(n)) return n;
    }
    for (const v of Object.values(o)) {
      const f = walk(v, depth + 1);
      if (f != null) return f;
    }
    return null;
  }
  return walk(root, 0);
}

function summarizeRoboflowForChat(result: unknown, overlayApplied: boolean, boxesDrawn: number): string {
  let extra = '';
  if (typeof result === 'object' && result != null && 'message' in result) {
    const m = (result as { message?: unknown }).message;
    if (typeof m === 'string' && m.trim()) extra = m.trim();
  }
  const statusParts: string[] = [];
  if (boxesDrawn > 0) {
    statusParts.push(
      '已在地图绘制地物轮廓（与 TiTiler 预览图对齐，略有几何误差属正常）。点击轮廓或框体可查看该目标面积。',
    );
  }
  if (overlayApplied) {
    statusParts.push('已叠加分析结果影像（半透明）。');
  }
  const total = findWorkflowTotalCount(result);
  if (total != null) {
    statusParts.push(`工作流返回 total_count：${total}。`);
  }
  if (statusParts.length === 0) {
    statusParts.push(
      '未解析到叠加影像或检测框；请配置 roboflow.inference-model-id、环境变量 ROBOFLOW_MODEL_ID，或前端 .env 的 VITE_ROBOFLOW_MODEL_ID（形如 my-project/1）。',
    );
  }
  const raw =
    extra ||
    (() => {
      try {
        return JSON.stringify(result).slice(0, 1200);
      } catch {
        return String(result);
      }
    })();
  return `Roboflow 推理已执行。${statusParts.join('')}\n${raw.length > 800 ? `${raw.slice(0, 800)}…` : raw}`;
}

/** 保证为 WGS84 经纬度（度），避免 Rectangle/fromDegrees 得到 undefined 或 NaN */
function sanitizeExtentDegrees(extent: Extent): Extent {
  const toNum = (v: unknown, name: string): number => {
    const n = typeof v === 'number' ? v : Number(v);
    if (!Number.isFinite(n)) throw new Error(`影像范围「${name}」无效（当前实现需要后端返回数字型经纬度）`);
    return n;
  };
  let w = toNum(extent.minLng, 'minLng');
  let s = toNum(extent.minLat, 'minLat');
  let e = toNum(extent.maxLng, 'maxLng');
  let n = toNum(extent.maxLat, 'maxLat');
  const projectedGuess = Math.max(Math.abs(w), Math.abs(e)) > 180 || Math.max(Math.abs(s), Math.abs(n)) > 90;
  if (projectedGuess) {
    throw new Error(
      '影像范围不像 WGS84 经纬度（度）。TiTiler /cog/info 的 bounds 若为投影坐标，需在服务端转为经纬度后再返回前端。',
    );
  }
  if (w > e) [w, e] = [e, w];
  if (s > n) [s, n] = [n, s];
  return {
    minLng: Math.max(-180, Math.min(180, w)),
    minLat: Math.max(-90, Math.min(90, s)),
    maxLng: Math.max(-180, Math.min(180, e)),
    maxLat: Math.max(-90, Math.min(90, n)),
  };
}

/** 影像像素 (x 向右、y 向下) → 上传 COG 经纬度范围（与检测框映射一致） */
function pixelXYToLngLat(px: number, py: number, imgW: number, imgH: number, safe: Extent): { lng: number; lat: number } {
  const dLng = safe.maxLng - safe.minLng;
  const dLat = safe.maxLat - safe.minLat;
  const lng = safe.minLng + (px / imgW) * dLng;
  const lat = safe.maxLat - (py / imgH) * dLat;
  return { lng, lat };
}

/** 按范围抬高起始 zoom；TiTiler 若返回 minzoom=0 时仍需与 heuristic 取较大值，少拖全球低清瓦片 */
function suggestMinZoomForRegionalExtent(extent: Extent, maximumLevel: number): number {
  const w = extent.maxLng - extent.minLng;
  const h = extent.maxLat - extent.minLat;
  const span = Math.max(w, h);
  let z = 0;
  if (span > 15) z = 0;
  else if (span > 3) z = 6;
  else if (span > 0.5) z = 10;
  else if (span > 0.05) z = 13;
  else z = 16;
  return Math.min(maximumLevel, Math.max(0, z));
}

type ChatMessage = {
  role: 'system' | 'user' | 'assistant';
  text: string;
  chartOption?: Record<string, unknown>;
  /** 多智能体分析进行中，显示闪烁思考样式 */
  thinking?: boolean;
  pendingKey?: string;
};
type MonthSelection = { year: number; month: number };
type LiveImageryResponse = {
  query: string;
  displayName: string;
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
  token: string;
  tileTemplateUrl: string;
  coverageRatio: number;
  boundaries: Array<Array<{ lng: number; lat: number }>>;
};
type ChangeLayerPayload = {
  tile_url?: string;
  tileTemplateUrl?: string;
  extent?: Extent | Record<string, unknown>;
  boundaries?: Array<Array<{ lng: number; lat: number }>>;
  tile_max_zoom?: number;
  tile_min_zoom?: number;
};
type ChangeLayersPayload = {
  mode?: string;
  left?: ChangeLayerPayload;
  right?: ChangeLayerPayload;
};
type NormalizedChangeLayer = {
  extent: Extent;
  tileTemplateUrl: string;
  boundaries: Array<Array<{ lng: number; lat: number }>>;
  tileMaxZoom?: number;
  tileMinZoom?: number;
};
type CroplandCompareParams = {
  place: string;
  startYear: number;
  startMonth: number;
  endYear: number;
  endMonth: number;
};
type AgentChatResponse = {
  answer: string;
  intent: string;
  chartOption?: Record<string, unknown> | null;
  data?: Record<string, unknown> | null;
};
type ApiErrorResponse = {
  status?: number;
  error?: string;
  message?: string;
  path?: string;
  timestamp?: string;
};

type RasterUploadResponse = {
  displayName: string;
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
  cogHttpUrl: string;
  tileTemplateUrl: string;
  /** TiTiler WebMercator maxzoom；用于 Cesium maximumLevel，避免近地只能放大低清瓦片 */
  tileMaxZoom?: number | null;
  /** TiTiler WebMercator minzoom；用于 Cesium minimumLevel，少加载全球低清瓦片 */
  tileMinZoom?: number | null;
};

function parseCroplandCompareParamsFromText(text: string): CroplandCompareParams | null {
  const normalized = text.trim();
  if (!/耕地|农田|cropland|farmland/i.test(normalized) || !/变化|change/i.test(normalized)) return null;

  const dateMatch = normalized.match(/(\d{4})\s*年\s*(\d{1,2})\s*月.*?(\d{4})\s*年\s*(\d{1,2})\s*月/);
  if (!dateMatch) return null;

  const startYear = Number(dateMatch[1]);
  const startMonth = Number(dateMatch[2]);
  const endYear = Number(dateMatch[3]);
  const endMonth = Number(dateMatch[4]);
  if (![startYear, startMonth, endYear, endMonth].every(Number.isFinite)) return null;
  if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) return null;

  const afterDates = normalized.slice((dateMatch.index ?? 0) + dateMatch[0].length);
  const beforeDates = normalized.slice(0, dateMatch.index ?? 0);
  const cleanPlace = (raw: string) =>
    raw
      .replace(/分析|对比|监测|统计|一下|请|帮我|的|耕地.*$/g, '')
      .replace(/[，。,.；;：:\s]/g, '')
      .trim();
  const place = cleanPlace(afterDates) || cleanPlace(beforeDates);
  if (!place) return null;

  return { place, startYear, startMonth, endYear, endMonth };
}

function ChartBubble({ option }: { option?: Record<string, unknown> }) {
  const chartRef = useRef<HTMLDivElement | null>(null);
  const chartInstanceRef = useRef<echarts.EChartsType | null>(null);

  useEffect(() => {
    return () => {
      chartInstanceRef.current?.dispose();
      chartInstanceRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!option || !chartRef.current) return;

    const instance = chartInstanceRef.current ?? echarts.init(chartRef.current);
    chartInstanceRef.current = instance;
    instance.setOption(option, true);
    window.requestAnimationFrame(() => instance.resize());

    const onResize = () => instance.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
    };
  }, [option]);

  if (!option) return null;
  return <div ref={chartRef} className="mt-2 h-56 w-full rounded border border-slate-200 bg-white" />;
}

export default function AIWorkspace({
  pendingSessionId = null,
  onPendingSessionConsumed,
  newWorkspaceNonce = 0,
}: AIWorkspaceProps) {
  const cesiumWrapRef = useRef<HTMLDivElement | null>(null);
  const cesiumLeftContainerRef = useRef<HTMLDivElement | null>(null);
  const cesiumRightContainerRef = useRef<HTMLDivElement | null>(null);

  const viewerLeftRef = useRef<Viewer | null>(null);
  const viewerRightRef = useRef<Viewer | null>(null);
  const initialBackgroundLeftRef = useRef<Color | null>(null);
  const initialBackgroundRightRef = useRef<Color | null>(null);
  const initialGlobeBaseLeftRef = useRef<Color | null>(null);
  const initialGlobeBaseRightRef = useRef<Color | null>(null);
  const baseLayersLeftRef = useRef<ImageryLayer[]>([]);
  const baseLayersRightRef = useRef<ImageryLayer[]>([]);
  const imageryLayerLeftRef = useRef<ImageryLayer | null>(null);
  const imageryLayerRightRef = useRef<ImageryLayer | null>(null);
  /** Roboflow 等地物分析结果单瓦片叠加层（盖在上传 COG 之上） */
  const roboflowOverlayLayerRef = useRef<ImageryLayer | null>(null);
  /** 自建 Inference 返回的检测框实体 */
  const roboflowDetectionEntitiesRef = useRef<Entity[]>([]);
  /** 实体 → 点击后展示的面积文案与标签锚点 */
  const roboflowPickMetaRef = useRef<Map<Entity, RoboflowPickMeta>>(new Map());
  /** 当前仅展示一条面积标签（点击切换） */
  const roboflowAreaLabelEntityRef = useRef<Entity | null>(null);
  /** 最近一次本地上传的 COG URL 与范围，用于工作流分析且不清除底图 */
  const lastUploadContextRef = useRef<LastUploadContext | null>(null);
  const lastRoboflowWorkflowJsonRef = useRef<unknown | null>(null);
  /** 防止并发多条地物分析导致 finishAssistant 错配、地图重复重绘卡顿 */
  const roboflowWorkflowInProgressRef = useRef(false);
  /** 多智能体任务：仅在首次收到区域时正俯视飞到研究区，结束不再改相机 */
  const multiAgentCameraFlewRef = useRef(false);
  /** 耕地变化：当 agent 暂未带 change_layers 时，仅用用户原文兜底加载一次本地分屏影像 */
  const multiAgentCroplandFallbackLoadedRef = useRef(false);
  const croplandRestoreRef = useRef<WorkspacePersistedStateV1['croplandRestore']>(null);
  const uploadDisplayNameRef = useRef<string | null>(null);
  const activeSessionIdRef = useRef<string | null>(null);
  const restoreInProgressRef = useRef(false);
  const lastAppliedRemoteSessionIdRef = useRef<string | null>(null);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const boundaryMaskLeftEntitiesRef = useRef<Entity[]>([]);
  const boundaryMaskRightEntitiesRef = useRef<Entity[]>([]);
  const requestAbortRef = useRef<AbortController | null>(null);
  const draggingSplitRef = useRef(false);
  const [splitPercent, setSplitPercent] = useState<number>(50);
  const [analysisMode, setAnalysisMode] = useState<boolean>(false);
  /** 用户本地上传的遥感图层：勿在退出分析时误删 */
  const [customImageryMode, setCustomImageryMode] = useState<boolean>(false);
  const analysisModeRef = useRef<boolean>(analysisMode);
  const rasterFileInputRef = useRef<HTMLInputElement | null>(null);
  /** 本地上传瓦片首次报错时提示一次（大扁平 TIFF / TiTiler 不可达等） */
  const uploadTileErrorHintRef = useRef<boolean>(false);

  useEffect(() => {
    analysisModeRef.current = analysisMode;
  }, [analysisMode]);

  useLayoutEffect(() => {
    removeLegacyViewfinderOverlay();
  });

  useEffect(() => {
    removeLegacyViewfinderOverlay();
    const observer = new MutationObserver(() => {
      removeLegacyViewfinderOverlay();
    });
    observer.observe(document.body, { childList: true, subtree: true });

    const cleanupTimers = [
      window.setTimeout(removeLegacyViewfinderOverlay, 0),
      window.setTimeout(removeLegacyViewfinderOverlay, 250),
      window.setTimeout(removeLegacyViewfinderOverlay, 1000),
      window.setTimeout(removeLegacyViewfinderOverlay, 2500),
    ];

    return () => {
      observer.disconnect();
      for (const timer of cleanupTimers) window.clearTimeout(timer);
    };
  }, []);

  const [chatInput, setChatInput] = useState('');
  const [roboflowWorkflowBusy, setRoboflowWorkflowBusy] = useState(false);
  const [multiAgentBusy, setMultiAgentBusy] = useState(false);
  const [cesiumReady, setCesiumReady] = useState(false);
  const [sessionRestoring, setSessionRestoring] = useState(false);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([
    {
      role: 'system',
      text: '请输入自然语言遥感分析指令，例如：「分析太湖蓝藻面积」。多智能体将显示思考过程并返回报告；耕地变化、上传影像地物提取仍走原有能力。',
    },
  ]);
  const rasterApiBase =
    (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_RASTER_API_URL ||
    'http://localhost:8080';
  const { runAnalysis: runMultiAgentAnalysis, disconnect: disconnectMultiAgent } =
    useMultiAgentTaskRunner(rasterApiBase);

  useEffect(() => () => disconnectMultiAgent(), [disconnectMultiAgent]);

  /** 浏览器直接请求 TiTiler 瓦片用的基址（可与后端 live.titiler 不同，例如局域网 IP） */
  const titilerPublicBase =
    (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_TITILER_URL ||
    '';

  function removeLegacyViewfinderOverlay() {
    const searchRoots = [cesiumWrapRef.current?.parentElement, document.body].filter(
      (root): root is HTMLElement => root != null,
    );

    for (const root of searchRoots) {
      for (const el of root.querySelectorAll('div')) {
        const classes = el.classList;
        const isLegacyViewfinder =
          classes.contains('absolute') &&
          classes.contains('top-1/2') &&
          classes.contains('left-1/2') &&
          classes.contains('-translate-x-1/2') &&
          classes.contains('-translate-y-1/2') &&
          classes.contains('w-48') &&
          classes.contains('h-48') &&
          classes.contains('pointer-events-none');
        if (!isLegacyViewfinder) continue;

        let hasCornerMarks = false;
        for (const child of el.children) {
          const childClasses = child.classList;
          if (
            (childClasses.contains('border-t-2') && childClasses.contains('border-l-2')) ||
            (childClasses.contains('border-b-2') && childClasses.contains('border-r-2'))
          ) {
            hasCornerMarks = true;
            break;
          }
        }
        if (hasCornerMarks) el.remove();
      }
    }
  }

  /** 将后端返回的瓦片模板换成浏览器可请求的地址（开发时代理绕过 TiTiler 跨域） */
  function rewriteTileTemplateUrlForBrowser(template: string): string {
    const envObj = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
    const dev = Boolean(envObj?.DEV);
    const useProxy = envObj?.VITE_TITILER_USE_PROXY !== 'false';
    const proxyPrefix = (envObj?.VITE_TITILER_PROXY_PREFIX || '/titiler-proxy').replace(/\/$/, '') || '/titiler-proxy';
    const springBase = rasterApiBase.replace(/\/$/, '');

    if (template.startsWith('/api/live-imagery/tiles/')) {
      return `${springBase}${template}`;
    }

    try {
      const parsed = new URL(template);
      if (parsed.pathname.includes('/api/live-imagery/tiles/')) {
        return template;
      }
      // 开发默认：同源 /titiler-proxy → vite 转发到 TiTiler（见 vite.config.ts）
      if (dev && useProxy && typeof window !== 'undefined' && parsed.pathname.includes('/cog/')) {
        return `${window.location.origin}${proxyPrefix}${parsed.pathname}${parsed.search}`;
      }

      const base = titilerPublicBase.replace(/\/$/, '');
      if (base) {
        const want = new URL(base);
        return template.replace(`${parsed.protocol}//${parsed.host}`, `${want.protocol}//${want.host}`);
      }
    } catch {
      return template;
    }
    return template;
  }

  function buildDateRange(monthSelection: MonthSelection): { start: string; end: string } {
    const { year, month } = monthSelection;
    const mm = String(month).padStart(2, '0');
    const lastDay = new Date(year, month, 0).getDate();
    const dd = String(lastDay).padStart(2, '0');
    return {
      start: `${year}-${mm}-01`,
      end: `${year}-${mm}-${dd}`,
    };
  }

  async function queryLiveImagery(place: string, monthSelection?: MonthSelection): Promise<{
    displayName: string;
    extent: Extent;
    tileTemplateUrl: string;
    boundaries: Array<Array<{ lng: number; lat: number }>>;
  }> {
    const queryText = place.trim();
    if (!queryText) {
      throw new Error('请输入城市名称');
    }

    const params = new URLSearchParams({ place: queryText });
    if (monthSelection) {
      const range = buildDateRange(monthSelection);
      params.set('start', range.start);
      params.set('end', range.end);
      params.set('provider', 'local_gee');
    }
    const liveUrl = `${rasterApiBase.replace(/\/$/, '')}/api/live-imagery/by-place?${params.toString()}`;
    const liveRes = await fetch(liveUrl);
    if (!liveRes.ok) {
      let errorMessage = '';
      try {
        const errorJson = (await liveRes.json()) as ApiErrorResponse;
        errorMessage = errorJson.message || '';
      } catch {
        // ignore parse failure
      }
      if (liveRes.status === 404) {
        throw new Error(errorMessage || '该地区未找到可用影像，请换一个地名试试');
      }
      throw new Error(errorMessage || `实时影像检索失败: ${liveRes.status}`);
    }

    const live = (await liveRes.json()) as LiveImageryResponse;
    return {
      displayName: live.displayName,
      extent: {
        minLng: live.minLng,
        minLat: live.minLat,
        maxLng: live.maxLng,
        maxLat: live.maxLat,
      },
      tileTemplateUrl: `${rasterApiBase.replace(/\/$/, '')}${live.tileTemplateUrl}`,
      boundaries: Array.isArray(live.boundaries) ? live.boundaries : [],
    };
  }

  async function executeAiCommand(command: string): Promise<AgentChatResponse> {
    const url = `${rasterApiBase.replace(/\/$/, '')}/api/agent/chat`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: command }),
    });
    if (!res.ok) {
      let msg = `AI 指令执行失败: ${res.status}`;
      try {
        const err = (await res.json()) as ApiErrorResponse;
        msg = err.message || msg;
      } catch {
        // ignore parse failures
      }
      throw new Error(msg);
    }
    return (await res.json()) as AgentChatResponse;
  }

  async function loadRasterByCog(
    extent: Extent,
    tileTemplateUrl: string,
    boundaries: Array<Array<{ lng: number; lat: number }>>,
    viewer: Viewer,
    imageryLayerRef: { current: ImageryLayer | null },
    boundaryMaskEntitiesRef: { current: Entity[] },
    options?: {
      splitDirection?: SplitDirection;
      applyMask?: boolean;
      shouldFlyTo?: boolean;
      /** 退出「左右卷帘」分屏，避免单层影像在 split 管线里 rectangle 为 undefined */
      resetImagerySplit?: boolean;
      /** 与服务端 TiTiler maxzoom 对齐；缺省时用较高默认，避免糊成一片 */
      tileMaxZoom?: number;
      /** 与服务端 TiTiler minzoom 对齐（与 capped 启发式取 max，见 loadRasterByCog） */
      tileMinZoom?: number;
      /** 瓦片请求失败时在对话里提示（便于排查超大非 COG、VITE_TITILER_URL 等） */
      reportTileErrorsToChat?: boolean;
      skipAbortPrevious?: boolean;
    },
  ) {
    if (!viewer) return;

    if (!options?.skipAbortPrevious) {
      requestAbortRef.current?.abort();
      requestAbortRef.current = new AbortController();
    }

    if (!tileTemplateUrl || typeof tileTemplateUrl !== 'string') {
      throw new Error('瓦片模板 URL 无效');
    }

    const resolvedTileUrl = rewriteTileTemplateUrlForBrowser(tileTemplateUrl);

    const safeExtent = sanitizeExtentDegrees(extent);
    if (options?.resetImagerySplit) {
      viewer.scene.splitPosition = 1.0;
    }

    const targetRectangle = Rectangle.fromDegrees(
      safeExtent.minLng,
      safeExtent.minLat,
      safeExtent.maxLng,
      safeExtent.maxLat,
    );
    const rawMax = options?.tileMaxZoom;
    const fromServerMax =
      rawMax != null && Number.isFinite(rawMax) ? Math.min(24, Math.max(0, Math.floor(rawMax))) : null;
    let maximumLevel = fromServerMax ?? 22;

    const rawMin = options?.tileMinZoom;
    let fromServerMin =
      rawMin != null && Number.isFinite(rawMin) ? Math.min(24, Math.max(0, Math.floor(rawMin))) : null;
    if (fromServerMin != null && fromServerMin > maximumLevel) {
      fromServerMin = maximumLevel;
    }

    const heuristicMin = suggestMinZoomForRegionalExtent(safeExtent, maximumLevel);
    // 启发式抬得过高会禁止低级别 overview 瓦片，Cesium 只能打小瓦片 → 棋盘格一块块显；
    // 封顶后仍可与 TiTiler minzoom 取 max，兼顾「少拖无关低清」和「先整屏略糊再变清」。
    const overviewHeuristicCap = 8;
    const minimumLevel = Math.min(
      maximumLevel,
      Math.max(fromServerMin ?? 0, Math.min(heuristicMin, overviewHeuristicCap)),
    );

    // 后端 TiTiler 模板为 WebMercatorQuad，必须与 Web 墨卡托切分一致；默认 Geographic 会导致 x/y 全错、图层空白
    const provider = new UrlTemplateImageryProvider({
      url: resolvedTileUrl,
      tilingScheme: new WebMercatorTilingScheme(),
      rectangle: targetRectangle,
      tileWidth: 512,
      tileHeight: 512,
      minimumLevel,
      maximumLevel,
      enablePickFeatures: false,
    });

    provider.errorEvent.addEventListener((tileError) => {
      const isDev = Boolean((import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV);
      if (isDev) {
        console.warn('[Cesium imagery]', tileError);
      }
      if (options?.reportTileErrorsToChat && !uploadTileErrorHintRef.current) {
        uploadTileErrorHintRef.current = true;
        const detail =
          tileError?.error != null
            ? typeof tileError.error === 'string'
              ? tileError.error
              : String(tileError.error)
            : '';
        setChatMessages((prev) => [
          ...prev,
          {
            role: 'assistant',
            text:
              '检测到地图瓦片加载失败（浏览器无法取得 TiTiler 影像）。常见原因：① 超大扁平 GeoTIFF 未做 COG，TiTiler 读瓦片极慢或失败——请用 GDAL 转成云优化后再传：gdal_translate -of COG -co COMPRESS=DEFLATE 输入.tif 输出.tif；② 前端 .env 配置 VITE_TITILER_URL 为浏览器能打开的 TiTiler 地址；③ Network 里查看 /cog/tiles 是否 200。'
              + (detail ? ` 错误摘要：${detail.slice(0, 200)}` : ''),
          },
        ]);
      }
      if (tileError?.error && String(tileError.error).includes('404')) {
        tileError.retry = false;
        return;
      }
      if ((tileError?.timesRetried ?? 0) >= 1) tileError.retry = false;
    });

    if (imageryLayerRef.current) {
      try {
        viewer.imageryLayers.remove(imageryLayerRef.current, true);
      } catch {
        // ignore remove failure
      }
      imageryLayerRef.current = null;
    }

    if (options?.applyMask !== false) {
      clearBoundaryMask(viewer, boundaryMaskEntitiesRef);
      applyBoundaryMask(viewer, boundaries, targetRectangle, boundaryMaskEntitiesRef);
    }

    const layer = new ImageryLayer(provider, {
      splitDirection: options?.splitDirection ?? SplitDirection.NONE,
    });
    layer.alpha = 1;
    viewer.imageryLayers.add(layer);
    viewer.imageryLayers.raiseToTop(layer);
    imageryLayerRef.current = layer;
    // 正射俯视：与 Ion/底图「飞到范围」观感一致；flyToBoundingSphere 默认斜视，本地上传若关 fly 则更不会对齐
    if (options?.shouldFlyTo !== false) {
      viewer.camera.flyTo({
        destination: targetRectangle,
        orientation: {
          heading: 0,
          pitch: -CesiumMath.PI_OVER_TWO,
          roll: 0,
        },
        duration: 1.5,
      });
    }
    viewer.scene.requestRender();

  }

  function clearBoundaryMask(viewer: Viewer, boundaryMaskEntitiesRef: { current: Entity[] }) {
    for (const entity of boundaryMaskEntitiesRef.current) {
      viewer.entities.remove(entity);
    }
    boundaryMaskEntitiesRef.current = [];
  }

  function applyBoundaryMask(
    viewer: Viewer,
    boundaries: Array<Array<{ lng: number; lat: number }>>,
    fallbackRectangle: Rectangle,
    boundaryMaskEntitiesRef: { current: Entity[] },
  ) {
    const validPolygons = boundaries
      .map((polygon) =>
        polygon
          .filter((p) => Number.isFinite(p.lng) && Number.isFinite(p.lat))
          .map((p) => Cartesian3.fromDegrees(p.lng, p.lat)),
      )
      .filter((polygon) => polygon.length >= 3);

    const holes = validPolygons.map((ring) => new PolygonHierarchy(ring));
    if (holes.length === 0) {
      // Rectangle.* 为弧度，这里需要转成度再喂给 fromDegrees
      const west = CesiumMath.toDegrees(fallbackRectangle.west);
      const east = CesiumMath.toDegrees(fallbackRectangle.east);
      const south = CesiumMath.toDegrees(fallbackRectangle.south);
      const north = CesiumMath.toDegrees(fallbackRectangle.north);
      const bboxRing = [
        Cartesian3.fromDegrees(west, south),
        Cartesian3.fromDegrees(east, south),
        Cartesian3.fromDegrees(east, north),
        Cartesian3.fromDegrees(west, north),
      ];
      holes.push(new PolygonHierarchy(bboxRing));
    }

    const worldRing = [
      Cartesian3.fromDegrees(-179.999, -85),
      Cartesian3.fromDegrees(179.999, -85),
      Cartesian3.fromDegrees(179.999, 85),
      Cartesian3.fromDegrees(-179.999, 85),
    ];

    const outsideMask = viewer.entities.add({
      polygon: {
        hierarchy: new PolygonHierarchy(worldRing, holes),
        // 避免底图从透明区域透出
        material: Color.BLACK.withAlpha(1.0),
        outline: false,
        perPositionHeight: false,
      },
    });
    boundaryMaskEntitiesRef.current.push(outsideMask);

    for (const ring of holes) {
      const boundaryLine = viewer.entities.add({
        polyline: {
          positions: [...ring.positions, ring.positions[0]],
          width: 2.2,
          material: Color.YELLOW.withAlpha(0.92),
        },
      });
      boundaryMaskEntitiesRef.current.push(boundaryLine);
    }
  }

  function removeImageryLayerFromViewer(viewer: Viewer, imageryLayerRef: { current: ImageryLayer | null }) {
    if (!imageryLayerRef.current) return;
    try {
      const removed = viewer.imageryLayers.remove(imageryLayerRef.current, true);
      if (!removed) return;
    } catch {
      return;
    }
    imageryLayerRef.current = null;
  }

  function removeImageryLayerFromAnyViewer(imageryLayerRef: { current: ImageryLayer | null }) {
    const layer = imageryLayerRef.current;
    if (!layer) return;
    for (const viewer of [viewerLeftRef.current, viewerRightRef.current]) {
      if (!viewer || viewer.isDestroyed()) continue;
      try {
        if (viewer.imageryLayers.remove(layer, true)) {
          imageryLayerRef.current = null;
          return;
        }
      } catch {
        // 继续尝试其它 Viewer
      }
    }
  }

  function removeRoboflowOverlayFromViewer(viewer: Viewer | null) {
    if (!viewer) return;
    const layer = roboflowOverlayLayerRef.current;
    if (!layer) return;
    try {
      viewer.imageryLayers.remove(layer, true);
    } catch {
      // ignore
    }
    roboflowOverlayLayerRef.current = null;
  }

  function applyRoboflowOverlayOnViewer(viewer: Viewer, extent: Extent, imageUrl: string) {
    removeRoboflowOverlayFromViewer(viewer);
    const rect = Rectangle.fromDegrees(extent.minLng, extent.minLat, extent.maxLng, extent.maxLat);
    const tw = 512;
    const th = 512;
    const provider = new SingleTileImageryProvider({
      url: imageUrl,
      rectangle: rect,
      tileWidth: tw,
      tileHeight: th,
    });
    const layer = new ImageryLayer(provider, { alpha: 0.82 });
    viewer.imageryLayers.add(layer);
    viewer.imageryLayers.raiseToTop(layer);
    roboflowOverlayLayerRef.current = layer;
    viewer.scene.requestRender();
  }

  function clearRoboflowDetectionEntities(viewer: Viewer | null) {
    if (!viewer || viewer.isDestroyed()) return;
    const labelEnt = roboflowAreaLabelEntityRef.current;
    if (labelEnt) {
      try {
        viewer.entities.remove(labelEnt);
      } catch {
        // ignore
      }
      roboflowAreaLabelEntityRef.current = null;
    }
    roboflowPickMetaRef.current.clear();
    for (const e of roboflowDetectionEntitiesRef.current) {
      try {
        viewer.entities.remove(e);
      } catch {
        // ignore
      }
    }
    roboflowDetectionEntitiesRef.current = [];
  }

  /** 将 Inference JSON 中的像素多边形/框映射到地图；不在图上写死面积，点击实体后再显示标签 */
  function drawRoboflowDetectionsForPick(viewer: Viewer, extent: Extent, wfJson: unknown): number {
    const payloadLegacy = extractRoboflowPredictionsPayload(wfJson);
    const predsArr = findWorkflowPredictionsArray(wfJson);
    const inner = getWorkflowInnerRecord(wfJson);
    const img = inner?.image && typeof inner.image === 'object' ? (inner.image as Record<string, unknown>) : null;
    let imgW =
      typeof img?.width === 'number' && Number.isFinite(img.width) ? img.width : payloadLegacy?.imgW ?? 640;
    let imgH =
      typeof img?.height === 'number' && Number.isFinite(img.height) ? img.height : payloadLegacy?.imgH ?? 640;

    clearRoboflowDetectionEntities(viewer);
    const safe = sanitizeExtentDegrees(extent);
    let n = 0;

    const addMeta = (ent: Entity, meta: RoboflowPickMeta) => {
      roboflowPickMetaRef.current.set(ent, meta);
      roboflowDetectionEntitiesRef.current.push(ent);
    };

    const pxToCartesian = (px: number, py: number) => {
      const { lng, lat } = pixelXYToLngLat(px, py, imgW, imgH, safe);
      return Cartesian3.fromDegrees(lng, lat);
    };

    const ringCentroid = (positions: Cartesian3[]): Cartesian3 => {
      let x = 0;
      let y = 0;
      let z = 0;
      for (const c of positions) {
        x += c.x;
        y += c.y;
        z += c.z;
      }
      const len = positions.length;
      const sum = new Cartesian3(x / len, y / len, z / len);
      return Cartesian3.normalize(sum, sum);
    };

    if (predsArr && predsArr.length > 0) {
      for (const raw of predsArr) {
        if (n >= MAX_ROBOFLOW_MAP_ENTITIES) break;
        if (!raw || typeof raw !== 'object') continue;
        const o = raw as Record<string, unknown>;
        const areaText = formatRoboflowAreaLine(o);
        const polyPx = extractPolygonPixelsFromPrediction(o);
        if (polyPx) {
          const positions = polyPx.map(([px, py]) => pxToCartesian(px, py));
          const centroid = ringCentroid(positions);
          const ent = viewer.entities.add({
            polygon: {
              hierarchy: new PolygonHierarchy(positions),
              material: Color.LIME.withAlpha(0.06),
              outline: true,
              outlineColor: Color.LIME,
              outlineWidth: 2,
              heightReference: HeightReference.CLAMP_TO_GROUND,
            },
          });
          addMeta(ent, { areaText, centroid });
          n += 1;
          continue;
        }
        const x = Number(o.x);
        const y = Number(o.y);
        const w = Number(o.width);
        const h = Number(o.height);
        if (![x, y, w, h].every(Number.isFinite)) continue;
        const leftPx = x - w / 2;
        const rightPx = x + w / 2;
        const topPx = y - h / 2;
        const bottomPx = y + h / 2;
        const dLng = safe.maxLng - safe.minLng;
        const dLat = safe.maxLat - safe.minLat;
        const west = safe.minLng + (leftPx / imgW) * dLng;
        const east = safe.minLng + (rightPx / imgW) * dLng;
        const north = safe.maxLat - (topPx / imgH) * dLat;
        const south = safe.maxLat - (bottomPx / imgH) * dLat;
        const wClamped = Math.min(west, east);
        const eClamped = Math.max(west, east);
        const sClamped = Math.min(south, north);
        const nClamped = Math.max(south, north);
        const cLng = (wClamped + eClamped) / 2;
        const cLat = (sClamped + nClamped) / 2;
        const centroid = Cartesian3.fromDegrees(cLng, cLat);
        const ent = viewer.entities.add({
          rectangle: {
            coordinates: Rectangle.fromDegrees(wClamped, sClamped, eClamped, nClamped),
            material: Color.LIME.withAlpha(0.12),
            outline: true,
            outlineColor: Color.LIME,
            outlineWidth: 2,
            heightReference: HeightReference.CLAMP_TO_GROUND,
          },
        });
        addMeta(ent, { areaText, centroid });
        n += 1;
      }
    }

    if (n === 0 && payloadLegacy) {
      imgW = payloadLegacy.imgW;
      imgH = payloadLegacy.imgH;
      for (const p of payloadLegacy.predictions) {
        if (n >= MAX_ROBOFLOW_MAP_ENTITIES) break;
        const areaText = p.className ? `${p.className} · 面积未返回` : '面积未返回';
        const leftPx = p.x - p.width / 2;
        const rightPx = p.x + p.width / 2;
        const topPx = p.y - p.height / 2;
        const bottomPx = p.y + p.height / 2;
        const dLng = safe.maxLng - safe.minLng;
        const dLat = safe.maxLat - safe.minLat;
        const west = safe.minLng + (leftPx / imgW) * dLng;
        const east = safe.minLng + (rightPx / imgW) * dLng;
        const north = safe.maxLat - (topPx / imgH) * dLat;
        const south = safe.maxLat - (bottomPx / imgH) * dLat;
        const wClamped = Math.min(west, east);
        const eClamped = Math.max(west, east);
        const sClamped = Math.min(south, north);
        const nClamped = Math.max(south, north);
        const cLng = (wClamped + eClamped) / 2;
        const cLat = (sClamped + nClamped) / 2;
        const centroid = Cartesian3.fromDegrees(cLng, cLat);
        const ent = viewer.entities.add({
          rectangle: {
            coordinates: Rectangle.fromDegrees(wClamped, sClamped, eClamped, nClamped),
            material: Color.LIME.withAlpha(0.12),
            outline: true,
            outlineColor: Color.LIME,
            outlineWidth: 2,
            heightReference: HeightReference.CLAMP_TO_GROUND,
          },
        });
        addMeta(ent, { areaText, centroid });
        n += 1;
      }
    }

    viewer.scene.requestRender();
    return n;
  }

  async function loadCroplandAnalysisLayers(
    normalizedPlace: string,
    sy: number,
    sm: number,
    ey: number,
    em: number,
    splitPositionPct: number,
    options?: { skipCroplandRefUpdate?: boolean },
  ) {
    const leftViewer = viewerLeftRef.current;
    const rightViewer = viewerRightRef.current;
    if (!leftViewer || !normalizedPlace) return;
    removeLegacyViewfinderOverlay();

    for (const l of baseLayersLeftRef.current) l.show = false;
    for (const l of baseLayersRightRef.current) l.show = false;
    if (rightViewer) {
      removeImageryLayerFromAnyViewer(imageryLayerRightRef);
      clearBoundaryMask(rightViewer, boundaryMaskRightEntitiesRef);
    }

    const [leftLive, rightLive] = await Promise.all([
      queryLiveImagery(normalizedPlace, { year: sy, month: sm }),
      queryLiveImagery(normalizedPlace, { year: ey, month: em }),
    ]);

    await loadRasterByCog(
      leftLive.extent,
      leftLive.tileTemplateUrl,
      leftLive.boundaries,
      leftViewer,
      imageryLayerLeftRef,
      boundaryMaskLeftEntitiesRef,
      {
        splitDirection: SplitDirection.LEFT,
        applyMask: false,
        shouldFlyTo: true,
        skipAbortPrevious: true,
      },
    );

    await loadRasterByCog(
      rightLive.extent,
      rightLive.tileTemplateUrl,
      rightLive.boundaries,
      leftViewer,
      imageryLayerRightRef,
      boundaryMaskRightEntitiesRef,
      {
        splitDirection: SplitDirection.RIGHT,
        applyMask: false,
        shouldFlyTo: false,
        skipAbortPrevious: true,
      },
    );
    leftViewer.scene.splitPosition = Math.max(0.05, Math.min(0.95, splitPositionPct / 100));
    rightViewer?.scene && (rightViewer.scene.splitPosition = 1.0);
    setSplitPercent(Math.max(5, Math.min(95, splitPositionPct)));

    if (!options?.skipCroplandRefUpdate) {
      croplandRestoreRef.current = {
        place: normalizedPlace,
        startYear: sy,
        startMonth: sm,
        endYear: ey,
        endMonth: em,
      };
    }
  }

  function normalizeChangeLayerPayload(layer: unknown): NormalizedChangeLayer | null {
    if (!layer || typeof layer !== 'object') return null;
    const raw = layer as ChangeLayerPayload;
    const tileTemplateUrl = raw.tile_url || raw.tileTemplateUrl || '';
    const extentRaw = raw.extent;
    if (!tileTemplateUrl || !extentRaw || typeof extentRaw !== 'object') return null;
    const extentObj = extentRaw as Record<string, unknown>;
    const extent = {
      minLng: Number(extentObj.minLng ?? extentObj.min_lng),
      minLat: Number(extentObj.minLat ?? extentObj.min_lat),
      maxLng: Number(extentObj.maxLng ?? extentObj.max_lng),
      maxLat: Number(extentObj.maxLat ?? extentObj.max_lat),
    };
    if (![extent.minLng, extent.minLat, extent.maxLng, extent.maxLat].every(Number.isFinite)) return null;
    return {
      extent,
      tileTemplateUrl,
      boundaries: Array.isArray(raw.boundaries) ? raw.boundaries : [],
      tileMaxZoom: typeof raw.tile_max_zoom === 'number' ? raw.tile_max_zoom : undefined,
      tileMinZoom: typeof raw.tile_min_zoom === 'number' ? raw.tile_min_zoom : undefined,
    };
  }

  async function loadChangeLayersFromSnapshot(snap: TaskRedisSnapshot): Promise<boolean> {
    const leftViewer = viewerLeftRef.current;
    const rightViewer = viewerRightRef.current;
    if (!leftViewer) return false;
    removeLegacyViewfinderOverlay();

    const rawChangeLayers = snap.change_layers;
    if (rawChangeLayers && typeof rawChangeLayers === 'object') {
      const changeLayers = rawChangeLayers as ChangeLayersPayload;
      const left = normalizeChangeLayerPayload(changeLayers.left);
      const right = normalizeChangeLayerPayload(changeLayers.right);
      if (left && right) {
        setAnalysisMode(true);
        setCustomImageryMode(false);
        setSplitPercent(50);
        for (const l of baseLayersLeftRef.current) l.show = false;
        for (const l of baseLayersRightRef.current) l.show = false;
        if (rightViewer) {
          removeImageryLayerFromAnyViewer(imageryLayerRightRef);
          clearBoundaryMask(rightViewer, boundaryMaskRightEntitiesRef);
        }

        await loadRasterByCog(
          left.extent,
          left.tileTemplateUrl,
          left.boundaries,
          leftViewer,
          imageryLayerLeftRef,
          boundaryMaskLeftEntitiesRef,
          {
            splitDirection: SplitDirection.LEFT,
            applyMask: false,
            shouldFlyTo: true,
            tileMaxZoom: left.tileMaxZoom,
            tileMinZoom: left.tileMinZoom,
            skipAbortPrevious: true,
          },
        );
        await loadRasterByCog(
          right.extent,
          right.tileTemplateUrl,
          right.boundaries,
          leftViewer,
          imageryLayerRightRef,
          boundaryMaskRightEntitiesRef,
          {
            splitDirection: SplitDirection.RIGHT,
            applyMask: false,
            shouldFlyTo: false,
            tileMaxZoom: right.tileMaxZoom,
            tileMinZoom: right.tileMinZoom,
            skipAbortPrevious: true,
          },
        );
        leftViewer.scene.splitPosition = 0.5;
        if (rightViewer) rightViewer.scene.splitPosition = 1.0;
        return true;
      }
    }

    const croplandData = snap.cropland_data;
    if (croplandData && typeof croplandData === 'object') {
      const data = croplandData as Record<string, unknown>;
      const place = typeof data.place === 'string' ? data.place : '';
      const sy = Number(data.startYear);
      const sm = Number(data.startMonth);
      const ey = Number(data.endYear);
      const em = Number(data.endMonth);
      if (place && [sy, sm, ey, em].every(Number.isFinite)) {
        setAnalysisMode(true);
        setCustomImageryMode(false);
        await loadCroplandAnalysisLayers(place, sy, sm, ey, em, 50);
        return true;
      }
    }

    return false;
  }

  function buildWorkspaceState(): WorkspacePersistedStateV1 {
    return {
      version: 1,
      messages: chatMessages,
      uploadContext: lastUploadContextRef.current,
      uploadDisplayName: uploadDisplayNameRef.current,
      roboflowWorkflowJson: lastRoboflowWorkflowJsonRef.current,
      croplandRestore: croplandRestoreRef.current,
      analysisMode,
      customImageryMode,
      splitPercent,
    };
  }

  async function persistWorkspaceSession() {
    if (restoreInProgressRef.current) return;
    const state = buildWorkspaceState();
    if (!isPersistableWorkspaceState(state)) return;
    const title = deriveSessionTitle(state.messages, state.uploadDisplayName);
    const trimmed = stripLargeStringsForSave(state, 200_000) as WorkspacePersistedStateV1;
    const body = { title, state: trimmed };
    const base = rasterApiBase.replace(/\/$/, '');
    try {
      let id = activeSessionIdRef.current ?? sessionStorage.getItem(WORKSPACE_SESSION_STORAGE_KEY);
      if (!id) {
        const res = await fetch(`${base}/api/workspace-sessions`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        if (!res.ok) return;
        const j = (await res.json()) as { id?: string };
        if (j.id) {
          id = j.id;
          activeSessionIdRef.current = id;
          sessionStorage.setItem(WORKSPACE_SESSION_STORAGE_KEY, id);
        }
      } else {
        await fetch(`${base}/api/workspace-sessions/${id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
      }
    } catch {
      // 静默失败，避免打断对话
    }
  }

  function clearWorkspaceForBlankMap() {
    const left = viewerLeftRef.current;
    const right = viewerRightRef.current;
    if (left) {
      removeRoboflowOverlayFromViewer(left);
      clearRoboflowDetectionEntities(left);
      removeImageryLayerFromViewer(left, imageryLayerLeftRef);
      clearBoundaryMask(left, boundaryMaskLeftEntitiesRef);
      left.scene.splitPosition = 1.0;
    }
    if (right) {
      removeImageryLayerFromAnyViewer(imageryLayerRightRef);
      clearBoundaryMask(right, boundaryMaskRightEntitiesRef);
      right.scene.splitPosition = 1.0;
    }
  }

  async function applyWorkspaceStateFromRemote(state: WorkspacePersistedStateV1) {
    const left = viewerLeftRef.current;
    if (!left) return;

    restoreInProgressRef.current = true;
    try {
      clearWorkspaceForBlankMap();

      lastUploadContextRef.current = state.uploadContext;
      uploadDisplayNameRef.current = state.uploadDisplayName;
      lastRoboflowWorkflowJsonRef.current = state.roboflowWorkflowJson;
      croplandRestoreRef.current = state.croplandRestore;

      setChatMessages(state.messages);
      setAnalysisMode(state.analysisMode);
      setCustomImageryMode(state.customImageryMode);
      setSplitPercent(state.splitPercent);

      const showBase = () => {
        for (const l of baseLayersLeftRef.current) l.show = true;
        for (const l of baseLayersRightRef.current) l.show = true;
      };

      const restoredCropland =
        state.croplandRestore ??
        [...state.messages]
          .reverse()
          .filter((m) => m.role === 'user')
          .map((m) => parseCroplandCompareParamsFromText(m.text))
          .find((v): v is CroplandCompareParams => v != null) ??
        null;

      if ((state.analysisMode || restoredCropland) && restoredCropland) {
        const r = restoredCropland;
        croplandRestoreRef.current = r;
        setAnalysisMode(true);
        setCustomImageryMode(false);
        setSplitPercent(state.analysisMode ? state.splitPercent : 50);
        await loadCroplandAnalysisLayers(
          r.place,
          r.startYear,
          r.startMonth,
          r.endYear,
          r.endMonth,
          state.analysisMode ? state.splitPercent : 50,
          { skipCroplandRefUpdate: true },
        );
      } else {
        showBase();
        if (state.customImageryMode && state.uploadContext) {
          const u = state.uploadContext;
          uploadTileErrorHintRef.current = false;
          await loadRasterByCog(
            u.extent,
            u.tileTemplateUrl,
            [],
            left,
            imageryLayerLeftRef,
            boundaryMaskLeftEntitiesRef,
            {
              applyMask: false,
              shouldFlyTo: true,
              resetImagerySplit: true,
              tileMaxZoom: u.tileMaxZoom ?? undefined,
              tileMinZoom: u.tileMinZoom ?? undefined,
              reportTileErrorsToChat: true,
            },
          );
          const wf = state.roboflowWorkflowJson;
          if (wf) {
            const overlayUrl = extractRoboflowOverlayImageUrl(wf);
            if (overlayUrl) {
              applyRoboflowOverlayOnViewer(left, u.extent, overlayUrl);
            }
            drawRoboflowDetectionsForPick(left, u.extent, wf);
          }
        }
      }

      left.scene.requestRender();
    } finally {
      restoreInProgressRef.current = false;
    }
  }

  function startNewWorkspaceSession() {
    removeLegacyViewfinderOverlay();
    disconnectMultiAgent();
    setMultiAgentBusy(false);
    multiAgentCameraFlewRef.current = false;
    sessionStorage.removeItem(WORKSPACE_SESSION_STORAGE_KEY);
    activeSessionIdRef.current = null;
    lastAppliedRemoteSessionIdRef.current = null;
    lastUploadContextRef.current = null;
    uploadDisplayNameRef.current = null;
    lastRoboflowWorkflowJsonRef.current = null;
    croplandRestoreRef.current = null;
    uploadTileErrorHintRef.current = false;
    setAnalysisMode(false);
    setCustomImageryMode(false);
    setSplitPercent(50);
    setChatMessages([
      {
        role: 'system',
        text: '请输入自然语言遥感分析指令，例如：「分析太湖蓝藻面积」。多智能体将显示思考过程并返回报告；耕地变化、上传影像地物提取仍走原有能力。',
      },
    ]);
    const left = viewerLeftRef.current;
    const right = viewerRightRef.current;
    if (left) {
      clearWorkspaceForBlankMap();
      for (const l of baseLayersLeftRef.current) l.show = true;
    }
    if (right) {
      for (const l of baseLayersRightRef.current) l.show = true;
    }
    left?.scene.requestRender();
  }

  async function handleRasterFileSelected(file: File | null) {
    if (!file) return;
    const leftViewer = viewerLeftRef.current;
    if (!leftViewer) return;
    removeLegacyViewfinderOverlay();

    const lower = file.name.toLowerCase();
    if (!/\.(tif|tiff|geotiff|cog)$/.test(lower)) {
      setChatMessages((prev) => [...prev, { role: 'assistant', text: '仅支持 .tif / .tiff / .geotiff / .cog 影像文件。' }]);
      return;
    }

    setChatMessages((prev) => [...prev, { role: 'user', text: `[上传影像] ${file.name}` }]);

    try {
      const formData = new FormData();
      formData.append('file', file);
      const res = await fetch(`${rasterApiBase.replace(/\/$/, '')}/api/upload/raster`, {
        method: 'POST',
        body: formData,
      });
      if (!res.ok) {
        let msg = `上传失败: ${res.status}`;
        const raw = await res.text();
        try {
          const err = JSON.parse(raw) as ApiErrorResponse;
          msg = err.message || err.error || msg;
        } catch {
          if (raw.trim()) msg = `${msg} — ${raw.trim().slice(0, 500)}`;
        }
        throw new Error(msg);
      }
      const data = (await res.json()) as RasterUploadResponse;

      removeImageryLayerFromViewer(leftViewer, imageryLayerLeftRef);
      if (viewerRightRef.current) {
        removeImageryLayerFromAnyViewer(imageryLayerRightRef);
        clearBoundaryMask(viewerRightRef.current, boundaryMaskRightEntitiesRef);
      }
      removeRoboflowOverlayFromViewer(leftViewer);
      clearRoboflowDetectionEntities(leftViewer);
      clearBoundaryMask(leftViewer, boundaryMaskLeftEntitiesRef);

      setAnalysisMode(false);
      setCustomImageryMode(true);
      uploadTileErrorHintRef.current = false;

      await loadRasterByCog(
        { minLng: data.minLng, minLat: data.minLat, maxLng: data.maxLng, maxLat: data.maxLat },
        data.tileTemplateUrl,
        [],
        leftViewer,
        imageryLayerLeftRef,
        boundaryMaskLeftEntitiesRef,
        {
          applyMask: false,
          shouldFlyTo: true,
          resetImagerySplit: true,
          tileMaxZoom: data.tileMaxZoom ?? undefined,
          tileMinZoom: data.tileMinZoom ?? undefined,
          reportTileErrorsToChat: true,
        },
      );

      lastUploadContextRef.current = {
        cogHttpUrl: data.cogHttpUrl,
        extent: {
          minLng: data.minLng,
          minLat: data.minLat,
          maxLng: data.maxLng,
          maxLat: data.maxLat,
        },
        tileTemplateUrl: data.tileTemplateUrl,
        tileMaxZoom: data.tileMaxZoom,
        tileMinZoom: data.tileMinZoom,
      };
      uploadDisplayNameRef.current = data.displayName;
      lastRoboflowWorkflowJsonRef.current = null;
      croplandRestoreRef.current = null;

      setChatMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: `已加载本地上传影像「${data.displayName}」。范围约 [${data.minLng.toFixed(4)}, ${data.minLat.toFixed(4)}] – [${data.maxLng.toFixed(4)}, ${data.maxLat.toFixed(4)}]。可输入 building, road, water 等地物指令做 Roboflow 分析（结果叠在本影像上）。仅「耕地面积变化」类分析会切换为服务端分屏影像。`,
        },
      ]);
    } catch (e) {
      setChatMessages((prev) => [
        ...prev,
        { role: 'assistant', text: `本地上传影像加载失败：${e instanceof Error ? e.message : '未知错误'}` },
      ]);
    }
  }

  async function handleSendMessage() {
    const text = chatInput.trim();
    if (!text) return;
    removeLegacyViewfinderOverlay();

    const isRoboflowIntent = looksRoboflowFeatureInstruction(text);
    if (isRoboflowIntent && roboflowWorkflowInProgressRef.current) {
      setChatInput('');
      setChatMessages((prev) => [
        ...prev,
        { role: 'user', text },
        {
          role: 'assistant',
          text: '上一条地物分析仍在进行中，请等待完成后再发送新的地物指令。',
        },
      ]);
      return;
    }

    const leftViewerForCmd = viewerLeftRef.current;

    setChatInput('');
    setChatMessages((prev) => [...prev, { role: 'user', text }]);

    if (!lastUploadContextRef.current && isRoboflowIntent) {
      setChatMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: '请先上传本地遥感影像，再发送 building / road / water 或「建筑、道路、水体」等地物提取指令。若刚做过「耕地面积变化」分析，本地影像上下文会被清空，需重新上传 .tif/.tiff。',
        },
      ]);
      return;
    }

    if (lastUploadContextRef.current && isRoboflowIntent) {
      const ctx = lastUploadContextRef.current;
      roboflowWorkflowInProgressRef.current = true;
      setRoboflowWorkflowBusy(true);
      setChatMessages((prev) => [...prev, { role: 'assistant', text: ROBOFLOW_PENDING_MESSAGE }]);

      const finishAssistant = (body: ChatMessage) => {
        setChatMessages((prev) => {
          // 不能用「仅看最后一条」：等地物分析时 Cesium 瓦片失败会插入 assistant 提示，
          // 插在 pending 之后会导致永远匹配不到 pending，界面一直停在「正在调用…」。
          let pendingIdx = -1;
          for (let i = prev.length - 1; i >= 0; i--) {
            const m = prev[i];
            if (m?.role === 'assistant' && m.text === ROBOFLOW_PENDING_MESSAGE) {
              pendingIdx = i;
              break;
            }
          }
          if (pendingIdx < 0) {
            return [...prev, body];
          }
          return [...prev.slice(0, pendingIdx), body, ...prev.slice(pendingIdx + 1)];
        });
      };

      const envObj = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
      const wfBody: Record<string, string | number> = {
        instruction: text,
        cogHttpUrl: ctx.cogHttpUrl,
      };
      const mid = envObj?.VITE_ROBOFLOW_MODEL_ID?.trim();
      if (mid) wfBody.modelId = mid;
      const inferBase = envObj?.VITE_ROBOFLOW_INFERENCE_BASE_URL?.trim();
      if (inferBase) wfBody.inferenceBaseUrl = inferBase.replace(/\/$/, '');
      const inferTask = envObj?.VITE_ROBOFLOW_INFERENCE_TASK?.trim();
      if (inferTask) wfBody.inferenceTask = inferTask;
      const ws = envObj?.VITE_ROBOFLOW_INFERENCE_WORKSPACE?.trim();
      if (ws) wfBody.workspaceName = ws;
      const wfid = envObj?.VITE_ROBOFLOW_INFERENCE_WORKFLOW_ID?.trim();
      if (wfid) wfBody.workflowId = wfid;
      const ppuRaw = envObj?.VITE_ROBOFLOW_PIXELS_PER_UNIT?.trim();
      if (ppuRaw) {
        const ppu = Number(ppuRaw.replace(/,/g, ''));
        if (Number.isFinite(ppu) && ppu > 0) wfBody.pixelsPerUnit = ppu;
      }

      const abortController = new AbortController();
      const timeoutId = window.setTimeout(() => abortController.abort(), ROBOFLOW_CLIENT_TIMEOUT_MS);
      try {
          const wfRes = await fetch(`${rasterApiBase.replace(/\/$/, '')}/api/roboflow/workflow`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(wfBody),
            signal: abortController.signal,
          });

          if (!wfRes.ok) {
            let msg = `Roboflow 工作流失败: ${wfRes.status}`;
            try {
              const err = (await wfRes.json()) as ApiErrorResponse;
              msg = err.message || msg;
            } catch {
              const raw = await wfRes.text();
              if (raw.trim()) msg = `${msg} — ${raw.trim().slice(0, 500)}`;
            }
            console.warn('[roboflow/workflow]', wfRes.status, msg);
            throw new Error(msg);
          }
          const wfJson = (await wfRes.json()) as unknown;
          lastRoboflowWorkflowJsonRef.current = wfJson;
          if (leftViewerForCmd) {
            clearRoboflowDetectionEntities(leftViewerForCmd);
          }
          const overlayUrl = extractRoboflowOverlayImageUrl(wfJson);
          if (leftViewerForCmd && overlayUrl) {
            applyRoboflowOverlayOnViewer(leftViewerForCmd, ctx.extent, overlayUrl);
          }
          let boxesDrawn = 0;
          if (leftViewerForCmd) {
            boxesDrawn = drawRoboflowDetectionsForPick(leftViewerForCmd, ctx.extent, wfJson);
          }
          if (!overlayUrl && boxesDrawn === 0) {
            console.warn(
              '[roboflow] 接口已返回但未解析到叠加影像 URL 与可绘制检测框。请在 Network 查看 POST …/api/roboflow/workflow 的响应体；或 GET /api/roboflow/diagnostics?cogHttpUrl=（粘贴上传返回的 cogHttpUrl，需 URL 编码）排查 TiTiler/COG。若 JSON 中图片字段名不在白名单，需扩展 ROBOFLOW_OVERLAY_OUTPUT_KEYS。',
            );
          }
          finishAssistant({
            role: 'assistant',
            text: summarizeRoboflowForChat(wfJson, Boolean(overlayUrl && leftViewerForCmd), boxesDrawn),
          });
        } catch (e) {
          const aborted = e instanceof Error && e.name === 'AbortError';
          if (aborted) {
            finishAssistant({
              role: 'assistant',
              text: `地物分析已取消或超时（前端等待约 ${Math.round(ROBOFLOW_CLIENT_TIMEOUT_MS / 60_000)} 分钟）。Inference 可能在首次拉模型，请只发一条指令稍后再试。`,
            });
            return;
          }
          finishAssistant({
            role: 'assistant',
            text: `地物分析失败：${e instanceof Error ? e.message : '未知错误'}。若含 model manager lock：GPU 正在加载模型，请等待 2–5 分钟后只发一条 road/water/building 再试；勿多 Tab 连点。后端已串行排队并自动重试，仍失败可调大 roboflow.inference-retry-max。`,
          });
        } finally {
          window.clearTimeout(timeoutId);
          roboflowWorkflowInProgressRef.current = false;
          setRoboflowWorkflowBusy(false);
        }
      return;
    }

    if (shouldRunMultiAgentAnalysis(text)) {
      setMultiAgentBusy(true);
      multiAgentCameraFlewRef.current = false;
      multiAgentCroplandFallbackLoadedRef.current = false;
      setChatMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: `${MULTI_AGENT_THINKING_PREFIX}…\n\n正在提交任务…`,
          thinking: true,
          pendingKey: MULTI_AGENT_PENDING_KEY,
        },
      ]);

      const replaceMultiAgentPending = (body: ChatMessage) => {
        setChatMessages((prev) => {
          let pendingIdx = -1;
          for (let i = prev.length - 1; i >= 0; i--) {
            if (prev[i]?.pendingKey === MULTI_AGENT_PENDING_KEY) {
              pendingIdx = i;
              break;
            }
          }
          if (pendingIdx < 0) return [...prev, body];
          return [...prev.slice(0, pendingIdx), body, ...prev.slice(pendingIdx + 1)];
        });
      };

      const loadCroplandFallbackFromUserText = async () => {
        if (multiAgentCroplandFallbackLoadedRef.current) return false;
        const parsed = parseCroplandCompareParamsFromText(text);
        if (!parsed) return false;
        multiAgentCroplandFallbackLoadedRef.current = true;
        setAnalysisMode(true);
        setCustomImageryMode(false);
        await loadCroplandAnalysisLayers(
          parsed.place,
          parsed.startYear,
          parsed.startMonth,
          parsed.endYear,
          parsed.endMonth,
          50,
        );
        return true;
      };

      const leftViewer = viewerLeftRef.current;
      void runMultiAgentAnalysis(text, {
        onThinking: (displayText) => {
          replaceMultiAgentPending({
            role: 'assistant',
            text: displayText,
            thinking: true,
            pendingKey: MULTI_AGENT_PENDING_KEY,
          });
        },
        onSnapshot: (snap: TaskRedisSnapshot) => {
          if (snap.analysis_type === 'cropland_change' || snap.analysis_intent === 'cropland_change' || snap.change_layers) {
            void loadChangeLayersFromSnapshot(snap)
              .then((loaded) => {
                if (loaded) multiAgentCameraFlewRef.current = true;
              })
              .catch((e) => {
                const msg = e instanceof Error ? e.message : String(e);
                console.warn('[cropland/change_layers]', msg);
                replaceMultiAgentPending({
                  role: 'assistant',
                  text: `耕地变化影像加载失败：${msg}`,
                  thinking: false,
                });
              });
            return;
          }
          void loadCroplandFallbackFromUserText()
            .then((loaded) => {
              if (loaded) multiAgentCameraFlewRef.current = true;
            })
            .catch((e) => {
              const msg = e instanceof Error ? e.message : String(e);
              console.warn('[cropland/local-fallback]', msg);
              replaceMultiAgentPending({
                role: 'assistant',
                text: `耕地变化本地影像加载失败：${msg}`,
                thinking: false,
              });
            });
          if (!leftViewerForCmd || !(snap.tile_url || snap.download_url || snap.region_coords)) {
            return;
          }
          const flyNow = !multiAgentCameraFlewRef.current;
          void applyTaskSnapshotToViewer(
            leftViewerForCmd,
            snap,
            loadRasterByCog,
            imageryLayerLeftRef,
            boundaryMaskLeftEntitiesRef,
            { shouldFlyTo: flyNow },
          ).then(() => {
            if (flyNow) multiAgentCameraFlewRef.current = true;
          });
        },
        onDone: (snap) => {
          setMultiAgentBusy(false);
          const answer = buildFinalAnswerFromSnapshot(snap);
          replaceMultiAgentPending({
            role: 'assistant',
            text: answer,
            chartOption: snap.chartOption,
            thinking: false,
          });
          if (snap.analysis_type === 'cropland_change' || snap.analysis_intent === 'cropland_change' || snap.change_layers) {
            void loadChangeLayersFromSnapshot(snap).catch((e) => {
              const msg = e instanceof Error ? e.message : String(e);
              console.warn('[cropland/change_layers]', msg);
              setChatMessages((prev) => [...prev, { role: 'assistant', text: `耕地变化影像加载失败：${msg}` }]);
            });
            return;
          }
          void loadCroplandFallbackFromUserText().catch((e) => {
            const msg = e instanceof Error ? e.message : String(e);
            console.warn('[cropland/local-fallback]', msg);
            setChatMessages((prev) => [...prev, { role: 'assistant', text: `耕地变化本地影像加载失败：${msg}` }]);
          });
          if (leftViewer) {
            void applyTaskSnapshotToViewer(
              leftViewer,
              snap,
              loadRasterByCog,
              imageryLayerLeftRef,
              boundaryMaskLeftEntitiesRef,
              { shouldFlyTo: false },
            );
          }
        },
        onError: (msg) => {
          setMultiAgentBusy(false);
          replaceMultiAgentPending({
            role: 'assistant',
            text: `智能体分析失败：${msg}。请确认 Redis、agent-api(8001) 与 Spring(8080) 已启动。`,
            thinking: false,
          });
        },
      });
      return;
    }

    try {
      const result = await executeAiCommand(text);

      const enableAnalysis = result.intent === 'cropland_change' && result.data;
      setAnalysisMode(Boolean(enableAnalysis));
      if (enableAnalysis) setSplitPercent(50);
      // 仅耕地变化分屏分析时替换上传图层；其它聊天指令保留本地上传影像
      if (enableAnalysis) {
        setCustomImageryMode(false);
        removeRoboflowOverlayFromViewer(leftViewerForCmd);
        clearRoboflowDetectionEntities(leftViewerForCmd);
        lastUploadContextRef.current = null;
        lastRoboflowWorkflowJsonRef.current = null;
        uploadDisplayNameRef.current = null;
      }

      // 如果是“耕地变化”，同时恢复加载影像底图（让原本的 Cesium 显示功能不丢）
      if (result.intent === 'cropland_change' && result.data) {
        const placeRaw = result.data['place'];
        const syRaw = result.data['startYear'];
        const smRaw = result.data['startMonth'];
        const eyRaw = result.data['endYear'];
        const emRaw = result.data['endMonth'];

        const place = typeof placeRaw === 'string' ? placeRaw : '';
        const sy = typeof syRaw === 'number' ? syRaw : Number(syRaw);
        const sm = typeof smRaw === 'number' ? smRaw : Number(smRaw);
        const ey = typeof eyRaw === 'number' ? eyRaw : Number(eyRaw);
        const em = typeof emRaw === 'number' ? emRaw : Number(emRaw);

        // 额外防御：去掉可能的前缀“月”（例如某些兜底解析出现“月南京”）
        const normalizedPlace = place.startsWith('月') && place.length > 2 ? place.slice(1) : place;

        const leftViewer = viewerLeftRef.current;
        if (
          normalizedPlace &&
          leftViewer &&
          Number.isFinite(sy) &&
          Number.isFinite(sm) &&
          Number.isFinite(ey) &&
          Number.isFinite(em)
        ) {
          const splitPct = enableAnalysis ? 50 : splitPercent;
          await loadCroplandAnalysisLayers(normalizedPlace, sy, sm, ey, em, splitPct);
        }
      }

      setChatMessages((prev) => [
        ...prev,
        { role: 'assistant', text: result.answer || '已处理。', chartOption: result.chartOption || undefined },
      ]);
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      setChatMessages((prev) => [
        ...prev,
        { role: 'assistant', text: `影像加载失败：${e instanceof Error ? e.message : '未知错误'}` },
      ]);
    }
  }

  useEffect(() => {
    if (!cesiumLeftContainerRef.current || !cesiumRightContainerRef.current) return;
    if (viewerLeftRef.current || viewerRightRef.current) return;

    Ion.defaultAccessToken =
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiI5MzUzYzQ3YS04ODA5LTQ4NjYtYTE3YS02ZmVlMGE5YzM5YWQiLCJpZCI6NDIxMjQ0LCJpYXQiOjE3NzY3Njk4NjB9.Upl_FpBGk7HAZdk6m_WK8wc-PJXGHVPqjGAUoBbJZV0';

    // Bing（含「经 Ion 的 Bing」）会直连 virtualearth.net，浏览器 XHR 常无 CORS；名称与说明里带 Bing 的一律不出现在选择器
    const imageryProviderViewModels = createDefaultImageryProviderViewModels().filter((vm) => {
      const name = vm.name ?? '';
      const tip = vm.tooltip ?? '';
      if (/bing/i.test(name)) return false;
      if (/bing\s+maps/i.test(tip) || /virtualearth\.net/i.test(tip)) return false;
      return true;
    });
    const selectedImageryProviderViewModel =
      imageryProviderViewModels.find((vm) => vm.name === 'ArcGIS World Imagery') ?? imageryProviderViewModels[0];

    const leftViewer = new Viewer(cesiumLeftContainerRef.current, {
      animation: false,
      baseLayerPicker: true,
      imageryProviderViewModels,
      selectedImageryProviderViewModel,
      // 防止左侧画布在合成时出现透明帧，导致底下右侧影像“透上来”
      contextOptions: {
        webgl: { alpha: false },
      },
      fullscreenButton: false,
      geocoder: false,
      homeButton: true,
      sceneModePicker: true,
      timeline: false,
      navigationHelpButton: false,
      infoBox: false,
      selectionIndicator: false,
      shouldAnimate: true,
    });

    const rightViewer = new Viewer(cesiumRightContainerRef.current, {
      animation: false,
      baseLayerPicker: true,
      imageryProviderViewModels,
      selectedImageryProviderViewModel,
      useDefaultRenderLoop: false,
      fullscreenButton: false,
      geocoder: false,
      homeButton: true,
      sceneModePicker: true,
      timeline: false,
      navigationHelpButton: false,
      infoBox: false,
      selectionIndicator: false,
      shouldAnimate: true,
    });

    // 非分析状态：保留默认底图
    // 分析时再在 handleSendMessage 里移除底图并只显示分析影像

    const destination = Cartesian3.fromDegrees(116.4074, 39.9042, 1200000);
    leftViewer.camera.flyTo({ destination, duration: 0.01 });
    rightViewer.camera.flyTo({ destination, duration: 0.01 });

    // Range 修复后 TiTiler 已快：MSSE 略放宽（1.5）避免一次性排队海量高清瓦片拖死首屏
    for (const v of [leftViewer, rightViewer]) {
      v.scene.globe.maximumScreenSpaceError = 1.5;
      const g = v.scene.globe as { tileCacheSize?: number };
      if (typeof g.tileCacheSize === 'number') {
        g.tileCacheSize = Math.max(g.tileCacheSize, 400);
      }
    }

    viewerLeftRef.current = leftViewer;
    viewerRightRef.current = rightViewer;

    const roboflowPickHandler = new ScreenSpaceEventHandler(leftViewer.scene.canvas);
    roboflowPickHandler.setInputAction((click) => {
      if (leftViewer.isDestroyed()) return;
      const removeAreaLabel = () => {
        const le = roboflowAreaLabelEntityRef.current;
        if (le && !leftViewer.isDestroyed()) {
          try {
            leftViewer.entities.remove(le);
          } catch {
            // ignore
          }
          roboflowAreaLabelEntityRef.current = null;
        }
      };
      const picked = leftViewer.scene.pick(click.position);
      if (!defined(picked) || !picked.id) {
        removeAreaLabel();
        leftViewer.scene.requestRender();
        return;
      }
      const ent = picked.id as Entity;
      const meta = roboflowPickMetaRef.current.get(ent);
      if (!meta) {
        removeAreaLabel();
        leftViewer.scene.requestRender();
        return;
      }
      removeAreaLabel();
      const labelEnt = leftViewer.entities.add({
        position: meta.centroid,
        label: {
          text: meta.areaText,
          font: '14px sans-serif',
          fillColor: Color.YELLOW,
          outlineColor: Color.BLACK,
          outlineWidth: 3,
          style: LabelStyle.FILL_AND_OUTLINE,
          verticalOrigin: VerticalOrigin.BOTTOM,
          pixelOffset: new Cartesian2(0, -6),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
      });
      roboflowAreaLabelEntityRef.current = labelEnt;
      leftViewer.scene.requestRender();
    }, ScreenSpaceEventType.LEFT_CLICK);

    // 记录初始默认底图 imagery layers（用于 analysisMode 开启/关闭时显隐）
    const captureBaseLayers = (viewer: Viewer) => {
      const arr: ImageryLayer[] = [];
      try {
        if (viewer.isDestroyed()) return arr;
        const layers = viewer.imageryLayers;
        for (let i = 0; i < layers.length; i++) {
          const layer = layers.get(i);
          if (layer) arr.push(layer);
        }
      } catch {
        // viewer 可能在异步流程中已被销毁，直接返回空集合
      }
      return arr;
    };
    baseLayersLeftRef.current = captureBaseLayers(leftViewer);
    baseLayersRightRef.current = captureBaseLayers(rightViewer);

    initialBackgroundLeftRef.current = leftViewer.scene.backgroundColor?.clone?.() ?? leftViewer.scene.backgroundColor;
    initialBackgroundRightRef.current = rightViewer.scene.backgroundColor?.clone?.() ?? rightViewer.scene.backgroundColor;
    initialGlobeBaseLeftRef.current = leftViewer.scene.globe.baseColor?.clone?.() ?? leftViewer.scene.globe.baseColor;
    initialGlobeBaseRightRef.current = rightViewer.scene.globe.baseColor?.clone?.() ?? rightViewer.scene.globe.baseColor;

    // 首屏兜底：切勿使用 createWorldImageryAsync() —— 仍走 Ion→Bing→virtualearth.net，易触发 XHR CORS。
    // 改用 ArcGIS World Imagery（与 BaseLayerPicker 默认一致）。
    let alive = true;
    void (async () => {
      try {
        if (!alive || leftViewer.isDestroyed()) return;
        if (leftViewer.imageryLayers.length === 0) {
          const layer = ImageryLayer.fromProviderAsync(
            ArcGisMapServerImageryProvider.fromBasemapType(ArcGisBaseMapType.SATELLITE, {
              enablePickFeatures: false,
            }),
          );
          leftViewer.imageryLayers.add(layer);
        }
      } catch {
        // ignore
      }

      try {
        if (!alive || leftViewer.isDestroyed()) return;
        // 不管是否已有 terrain，都尝试把它设置成世界地形（会自动兜底到 ellipsoid）
        leftViewer.terrainProvider = await createWorldTerrainAsync();
      } catch {
        // ignore
      }

      try {
        if (!alive || rightViewer.isDestroyed()) return;
        if (rightViewer.imageryLayers.length === 0) {
          const layer = ImageryLayer.fromProviderAsync(
            ArcGisMapServerImageryProvider.fromBasemapType(ArcGisBaseMapType.SATELLITE, {
              enablePickFeatures: false,
            }),
          );
          rightViewer.imageryLayers.add(layer);
        }
      } catch {
        // ignore
      }

      try {
        if (!alive || rightViewer.isDestroyed()) return;
        rightViewer.terrainProvider = await createWorldTerrainAsync();
      } catch {
        // ignore
      }

      if (!alive || leftViewer.isDestroyed() || rightViewer.isDestroyed()) return;
      if (!imageryLayerLeftRef.current && !imageryLayerRightRef.current && !customImageryMode) {
        // 仅在尚未加载分析影像时刷新默认底图列表，避免把耕地分屏影像误当底图隐藏。
        baseLayersLeftRef.current = captureBaseLayers(leftViewer);
        baseLayersRightRef.current = captureBaseLayers(rightViewer);
      }

      // 根据当前模式修正底图显隐
      const visible = !analysisModeRef.current;
      for (const l of baseLayersLeftRef.current) l.show = visible;
      for (const l of baseLayersRightRef.current) l.show = visible;

      leftViewer.resize();
      leftViewer.scene.requestRender();
    })();

    setCesiumReady(true);

    return () => {
      setCesiumReady(false);
      alive = false;
      roboflowPickHandler.destroy();
      requestAbortRef.current?.abort();
      requestAbortRef.current = null;

      if (viewerLeftRef.current) {
        clearBoundaryMask(viewerLeftRef.current, boundaryMaskLeftEntitiesRef);
        viewerLeftRef.current.destroy();
        viewerLeftRef.current = null;
      }
      if (viewerRightRef.current) {
        clearBoundaryMask(viewerRightRef.current, boundaryMaskRightEntitiesRef);
        viewerRightRef.current.destroy();
        viewerRightRef.current = null;
      }

      imageryLayerLeftRef.current = null;
      imageryLayerRightRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!cesiumReady) return;
    const targetId = (pendingSessionId?.trim() || sessionStorage.getItem(WORKSPACE_SESSION_STORAGE_KEY)?.trim()) || null;
    if (!targetId) return;
    if (!pendingSessionId && lastAppliedRemoteSessionIdRef.current === targetId) return;

    let cancelled = false;
    setSessionRestoring(true);
    void (async () => {
      try {
        const base = rasterApiBase.replace(/\/$/, '');
        const res = await fetch(`${base}/api/workspace-sessions/${targetId}`);
        if (!res.ok) {
          sessionStorage.removeItem(WORKSPACE_SESSION_STORAGE_KEY);
          lastAppliedRemoteSessionIdRef.current = null;
          if (!cancelled) {
            setChatMessages((prev) => [
              ...prev,
              { role: 'assistant', text: '无法恢复已存工作区（可能已删除或数据库未建表）。' },
            ]);
          }
          return;
        }
        const row = (await res.json()) as { state?: unknown };
        const parsed = coercePersistedState(row.state);
        if (!parsed || cancelled) return;
        lastAppliedRemoteSessionIdRef.current = targetId;
        sessionStorage.setItem(WORKSPACE_SESSION_STORAGE_KEY, targetId);
        activeSessionIdRef.current = targetId;
        await applyWorkspaceStateFromRemote(parsed);
        if (pendingSessionId && !cancelled) {
          onPendingSessionConsumed?.();
        }
      } catch {
        if (!cancelled) {
          setChatMessages((prev) => [
            ...prev,
            {
              role: 'assistant',
              text: '加载已存工作区失败：请确认后端已启动（Flyway 会自动建表）且数据库可访问。',
            },
          ]);
        }
      } finally {
        if (!cancelled) {
          setSessionRestoring(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
    // onPendingSessionConsumed 由 App 稳定化（useCallback），避免无谓重载
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cesiumReady, pendingSessionId, rasterApiBase]);

  useLayoutEffect(() => {
    if (newWorkspaceNonce <= 0) return;
    startNewWorkspaceSession();
    // startNewWorkspaceSession 为稳定重置逻辑，仅随 nonce 触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [newWorkspaceNonce]);

  useEffect(() => {
    if (!cesiumReady || sessionRestoring) return;
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      void persistWorkspaceSession();
    }, 800);
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    };
  }, [chatMessages, analysisMode, customImageryMode, splitPercent, cesiumReady, sessionRestoring]);

  // 耕地变化使用同一个 Viewer 的左右裁切图层，缩放和平移天然共享同一个相机。
  useEffect(() => {
    const left = viewerLeftRef.current;
    if (left && !left.isDestroyed()) {
      left.scene.splitPosition = Math.max(0.05, Math.min(0.95, splitPercent / 100));
      left.scene.requestRender();
    }
  }, [splitPercent, analysisMode]);

  useEffect(() => {
    // 切换 analysisMode 时也要触发布局重算
    viewerLeftRef.current?.resize();

    const leftViewer = viewerLeftRef.current;
    const rightViewer = viewerRightRef.current;
    if (!leftViewer || !rightViewer) return;

    const setAnalysisVisuals = (viewer: Viewer, enable: boolean) => {
      // 仅分析模式：白底 + 隐藏天空等；本地上传叠在默认底图上，不改变原先底图/天空观感
      if (enable) {
        viewer.scene.backgroundColor = Color.WHITE;
        viewer.scene.globe.baseColor = Color.WHITE;
      } else {
        const restoreBg = viewer === leftViewer ? initialBackgroundLeftRef.current : initialBackgroundRightRef.current;
        if (restoreBg) viewer.scene.backgroundColor = restoreBg;
        const restoreBase =
          viewer === leftViewer ? initialGlobeBaseLeftRef.current : initialGlobeBaseRightRef.current;
        if (restoreBase) viewer.scene.globe.baseColor = restoreBase;
      }

      const skyBox: any = (viewer.scene as any).skyBox;
      if (skyBox) skyBox.show = !enable;
      const skyAtmosphere: any = (viewer.scene as any).skyAtmosphere;
      if (skyAtmosphere) skyAtmosphere.show = !enable;
      const moon: any = (viewer.scene as any).moon;
      if (moon) moon.show = !enable;
      const sun: any = (viewer.scene as any).sun;
      if (sun) sun.show = !enable;
      viewer.scene.requestRender();
    };

    // 1) 切换底图显隐：analysisMode 只隐藏“默认底图层”，不移除它们
    const setBaseLayersVisible = (visible: boolean) => {
      for (const l of baseLayersLeftRef.current) l.show = visible;
      for (const l of baseLayersRightRef.current) l.show = visible;
    };

    if (analysisMode) {
      setBaseLayersVisible(false);
    } else {
      setBaseLayersVisible(true);

      // 2) 退出分析且非本地上传图层时，清理覆盖层，恢复仅默认底图
      if (!customImageryMode) {
        removeRoboflowOverlayFromViewer(leftViewer);
        clearRoboflowDetectionEntities(leftViewer);
        removeImageryLayerFromAnyViewer(imageryLayerLeftRef);
        removeImageryLayerFromAnyViewer(imageryLayerRightRef);

        clearBoundaryMask(leftViewer, boundaryMaskLeftEntitiesRef);
        clearBoundaryMask(rightViewer, boundaryMaskRightEntitiesRef);
      }
    }

    setAnalysisVisuals(leftViewer, analysisMode);
    setAnalysisVisuals(rightViewer, analysisMode);
  }, [analysisMode, customImageryMode]);

  function onSplitPointerDown(e: any) {
    const wrap = cesiumWrapRef.current;
    if (!wrap) return;
    const rect = wrap.getBoundingClientRect();
    if (rect.width <= 0) return;

    draggingSplitRef.current = true;

    const move = (ev: PointerEvent) => {
      if (!draggingSplitRef.current) return;
      const x = ev.clientX - rect.left;
      const pct = (x / rect.width) * 100;
      const clamped = Math.max(5, Math.min(95, pct));
      setSplitPercent(clamped);
    };

    const up = () => {
      draggingSplitRef.current = false;
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    e.preventDefault?.();
  }

  return (
    <div className="flex flex-row-reverse h-full w-full overflow-hidden bg-[#f8f9fa] text-slate-800">
      <section className="w-[40%] flex flex-col border-l border-slate-200 bg-white">
        <div className="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50 gap-2">
          <h2 className="text-sm font-bold font-headline shrink-0">Analysis Stream</h2>
          <div className="flex items-center gap-2 min-w-0">
            <button
              type="button"
              onClick={() => startNewWorkspaceSession()}
              className="text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded border border-slate-300 bg-white text-slate-600 hover:border-indigo-400 hover:text-indigo-600 shrink-0"
            >
              新会话
            </button>
            {sessionRestoring ? (
              <span className="text-[10px] text-amber-600 font-bold truncate">恢复中…</span>
            ) : null}
            <span className="text-[10px] px-2 py-0.5 bg-slate-200 text-slate-600 font-bold rounded uppercase truncate">
              MODEL: po-1.0.0
            </span>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4 custom-scrollbar">
          {chatMessages.map((message, index) => {
            const isUser = message.role === 'user';
            const isSystem = message.role === 'system';
            return (
              <div key={`${message.role}-${index}`} className={`flex gap-3 ${isUser ? 'flex-row-reverse' : ''}`}>
                <div
                  className={`w-8 h-8 rounded flex items-center justify-center flex-shrink-0 ${
                    isUser ? 'bg-slate-100 border border-slate-200' : 'bg-indigo-500 shadow-sm shadow-indigo-200'
                  }`}
                >
                  {isUser ? <User className="w-4 h-4 text-slate-500" /> : <Bot className="w-4 h-4 text-white" />}
            </div>
                <div
                  className={`rounded-lg p-3 max-w-[90%] text-xs leading-relaxed ${
                    isUser
                      ? 'bg-white border border-slate-200 text-slate-800 shadow-sm'
                      : isSystem
                        ? 'bg-slate-50 border border-slate-100 text-slate-600'
                        : 'bg-slate-50 border border-slate-100 text-slate-700 shadow-sm'
                  }`}
                >
                  <div className={`whitespace-pre-wrap ${message.thinking ? 'text-slate-600' : ''}`}>
                    {message.text}
                    {message.thinking ? (
                      <span className="inline-flex items-center gap-1 ml-2 align-middle" aria-hidden>
                        <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 animate-pulse" />
                        <span className="w-1.5 h-1.5 rounded-full bg-indigo-400 animate-pulse [animation-delay:150ms]" />
                        <span className="w-1.5 h-1.5 rounded-full bg-indigo-300 animate-pulse [animation-delay:300ms]" />
                      </span>
                    ) : null}
                  </div>
                  {message.role === 'assistant' ? <ChartBubble option={message.chartOption} /> : null}
            </div>
          </div>
            );
          })}
          </div>

        <div className="p-4 border-t border-slate-100">
          <div className="relative bg-white rounded-lg border border-slate-200 overflow-hidden focus-within:ring-2 focus-within:ring-indigo-500 transition-all shadow-sm">
            <textarea 
              className="w-full bg-transparent border-none focus:ring-0 text-xs p-3 h-20 resize-none placeholder:text-slate-400 custom-scrollbar" 
              placeholder="请输入你的指令..."
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key !== 'Enter' || e.shiftKey) return;
                // 中文输入法组字未结束时按 Enter 会提交拼音，不应当作发送
                if (e.nativeEvent.isComposing) return;
                e.preventDefault();
                void handleSendMessage();
              }}
            />
            <div className="flex items-center justify-between px-3 py-2 bg-slate-50 border-t border-slate-100">
              <div className="flex gap-2">
                <button type="button" className="text-slate-400 hover:text-indigo-500 transition-colors">
                  <ImageIcon className="w-3.5 h-3.5" />
                </button>
                <button type="button" className="text-slate-400 hover:text-indigo-500 transition-colors">
                  <RectangleHorizontal className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  className="text-slate-400 hover:text-indigo-500 transition-colors"
                  title="上传遥感影像 (.tif / .tiff)"
                  onClick={() => rasterFileInputRef.current?.click()}
                >
                  <Paperclip className="w-3.5 h-3.5" />
                </button>
                <input
                  ref={rasterFileInputRef}
                  type="file"
                  accept=".tif,.tiff,.geotiff,.cog,image/tiff"
                  className="hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0] ?? null;
                    e.target.value = '';
                    void handleRasterFileSelected(f);
                  }}
                />
              </div>
              <button
                className="bg-indigo-600 text-white p-1.5 rounded hover:bg-indigo-500 active:scale-95 transition-all shadow-sm disabled:bg-slate-300 disabled:cursor-not-allowed"
                onClick={() => void handleSendMessage()}
                disabled={!chatInput.trim() || roboflowWorkflowBusy || multiAgentBusy}
              >
                <Send className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>
      </section>

      <section className={`flex-1 relative overflow-hidden ${analysisMode ? 'bg-white' : 'bg-slate-100'}`}>
        <div ref={cesiumWrapRef} className="absolute inset-0 z-0 overflow-hidden">
          <div
            ref={cesiumLeftContainerRef}
            className="absolute top-0 bottom-0"
            style={{
              inset: 0,
              width: '100%',
              zIndex: 20,
              pointerEvents: analysisMode ? 'auto' : 'auto',
              backgroundColor: '#fff',
            }}
          />
          <div
            ref={cesiumRightContainerRef}
            className="absolute top-0 bottom-0 transition-[clip-path,opacity] duration-150"
            style={{
              inset: 0,
              width: '100%',
              display: 'none',
              zIndex: -1,
              opacity: 0,
              clipPath: 'inset(0 0 0 100%)',
              backgroundColor: '#fff',
              pointerEvents: 'none',
            }}
          />
          <div
            className="absolute top-0 bottom-0 w-px bg-white/90 z-30"
            style={{
              left: `${analysisMode ? splitPercent : 100}%`,
              pointerEvents: 'none',
              opacity: analysisMode ? 1 : 0,
            }}
          />
          <div
            className="absolute top-0 bottom-0 z-30"
            style={{
              left: `${analysisMode ? splitPercent : 100}%`,
              width: analysisMode ? 10 : 0,
              marginLeft: analysisMode ? -5 : 0,
              cursor: analysisMode ? 'col-resize' : 'default',
              touchAction: 'none',
            }}
            onPointerDown={analysisMode ? onSplitPointerDown : undefined}
          />
        </div>

        {/* <motion.div
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          className="absolute top-4 left-4 w-64 bg-white rounded-lg shadow-xl p-4 z-10 border border-slate-200"
        >
          <div className="flex items-center justify-between mb-4 pb-2 border-b border-slate-100">
            <h3 className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Layer Management</h3>
            <Settings className="w-3.5 h-3.5 text-slate-400 cursor-pointer hover:text-indigo-600" />
          </div>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-medium text-slate-700">Water Mask Result</span>
                <span className="text-[10px] font-mono text-emerald-500">75%</span>
              </div>
              <div className="h-1 bg-slate-100 rounded-full overflow-hidden">
                <motion.div initial={{ width: 0 }} animate={{ width: '75%' }} className="h-full bg-emerald-500" />
              </div>
            </div>
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-medium text-slate-700">Original Image</span>
                <span className="text-[10px] font-mono text-indigo-500 font-bold underline">100%</span>
              </div>
              <div className="h-1 bg-slate-100 rounded-full overflow-hidden">
                <motion.div initial={{ width: 0 }} animate={{ width: '100%' }} className="h-full bg-indigo-500" />
              </div>
            </div>
          </div>
        </motion.div> */}

        {/* <div className="absolute right-4 top-15 flex flex-col gap-2">
          <div className="bg-white border border-slate-200 rounded-md shadow-sm p-1 flex flex-col gap-1">
            <button className="p-1.5 hover:bg-slate-100 text-slate-400 hover:text-indigo-600 rounded"><ZoomIn className="w-4 h-4" /></button>
            <button className="p-1.5 hover:bg-slate-100 text-slate-400 hover:text-indigo-600 rounded"><ZoomOut className="w-4 h-4" /></button>
          </div>
          <div className="bg-white border border-slate-200 rounded-md shadow-sm p-1 flex flex-col gap-1">
            <button className="p-1.5 hover:bg-slate-100 text-slate-400 hover:text-indigo-600 rounded"><Ruler className="w-4 h-4" /></button>
            <button className="p-1.5 hover:bg-slate-100 text-slate-400 hover:text-indigo-600 rounded"><Rotate3d className="w-4 h-4" /></button>
          </div>
        </div>

        <div className="absolute bottom-4 left-4 right-4 flex items-end justify-between pointer-events-none">
          <div className="bg-white px-4 py-2 rounded-lg border border-slate-200 shadow-xl pointer-events-auto flex gap-6">
            <div className="flex flex-col">
              <span className="text-[9px] text-slate-400 font-bold uppercase tracking-wider">Coordinates</span>
              <span className="text-xs font-mono font-bold text-slate-800">39°54'15"N 116°24'27"E</span>
            </div>
            <div className="h-8 w-px bg-slate-200 shrink-0" />
            <div className="flex flex-col">
              <span className="text-[9px] text-slate-400 font-bold uppercase tracking-wider">MSL Elev.</span>
              <span className="text-xs font-mono font-bold text-slate-800">43.2m</span>
            </div>
          </div>
          <div className="bg-white px-3 py-1.5 rounded-md border border-slate-200 shadow-lg pointer-events-auto flex items-center gap-2">
            <div className="w-2 h-2 rounded-full bg-emerald-500 shadow-sm" />
            <span className="text-[9px] font-bold text-slate-500 uppercase tracking-widest">LIVE CONNECTION</span>
          </div>
        </div> */}

      </section>
    </div>
  );
}
