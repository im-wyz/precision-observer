import { GeoJsonDataSource, Color, Rectangle, Math as CesiumMath, type Viewer } from 'cesium';
import type { TaskRedisSnapshot } from '../api/tasks';
import { extractMapPayloadFromSnapshot, type Extent } from './cesiumTiles';

/** 与 AIWorkspace.loadRasterByCog 一致：正射俯视，不用 viewer.flyTo(entity) 的默认斜视 */
function flyToExtentNadir(viewer: Viewer, extent: Extent, duration = 1.2): void {
  viewer.camera.flyTo({
    destination: Rectangle.fromDegrees(extent.minLng, extent.minLat, extent.maxLng, extent.maxLat),
    orientation: {
      heading: 0,
      pitch: -CesiumMath.PI_OVER_TWO,
      roll: 0,
    },
    duration,
  });
}

export type LoadRasterFn = (
  extent: Extent,
  tileTemplateUrl: string,
  boundaries: Array<Array<{ lng: number; lat: number }>>,
  viewer: Viewer,
  imageryLayerRef: { current: import('cesium').ImageryLayer | null },
  boundaryMaskEntitiesRef: { current: import('cesium').Entity[] },
  options?: {
    shouldFlyTo?: boolean;
    tileMaxZoom?: number;
    tileMinZoom?: number;
    resetImagerySplit?: boolean;
  },
) => Promise<void>;

let geoSourceRef: GeoJsonDataSource | null = null;

function boundariesFromGeoJson(geojson: Record<string, unknown> | null): Array<Array<{ lng: number; lat: number }>> {
  if (!geojson) return [];
  const geometry =
    geojson.type === 'Feature' && geojson.geometry && typeof geojson.geometry === 'object'
      ? (geojson.geometry as Record<string, unknown>)
      : geojson;
  const type = geometry.type;
  const coordinates = geometry.coordinates;
  const rings: Array<Array<{ lng: number; lat: number }>> = [];

  const pushRing = (rawRing: unknown) => {
    if (!Array.isArray(rawRing)) return;
    const ring: Array<{ lng: number; lat: number }> = [];
    for (const pt of rawRing) {
      if (!Array.isArray(pt) || pt.length < 2) continue;
      const lng = Number(pt[0]);
      const lat = Number(pt[1]);
      if (Number.isFinite(lng) && Number.isFinite(lat)) ring.push({ lng, lat });
    }
    if (ring.length >= 3) rings.push(ring);
  };

  if (type === 'Polygon' && Array.isArray(coordinates)) {
    pushRing(coordinates[0]);
  } else if (type === 'MultiPolygon' && Array.isArray(coordinates)) {
    for (const polygon of coordinates) {
      if (Array.isArray(polygon)) pushRing(polygon[0]);
    }
  }
  return rings;
}

function boundariesFromSnapshot(value: unknown): Array<Array<{ lng: number; lat: number }>> {
  if (!Array.isArray(value)) return [];
  const rings: Array<Array<{ lng: number; lat: number }>> = [];
  for (const rawRing of value) {
    if (!Array.isArray(rawRing)) continue;
    const ring: Array<{ lng: number; lat: number }> = [];
    for (const pt of rawRing) {
      if (!pt || typeof pt !== 'object') continue;
      const o = pt as Record<string, unknown>;
      const lng = Number(o.lng);
      const lat = Number(o.lat);
      if (Number.isFinite(lng) && Number.isFinite(lat)) ring.push({ lng, lat });
    }
    if (ring.length >= 3) rings.push(ring);
  }
  return rings;
}

export async function applyTaskSnapshotToViewer(
  viewer: Viewer | null,
  snap: TaskRedisSnapshot,
  loadRaster: LoadRasterFn,
  imageryLayerRef: { current: import('cesium').ImageryLayer | null },
  boundaryEntitiesRef: { current: import('cesium').Entity[] },
  options?: { shouldFlyTo?: boolean },
): Promise<void> {
  const shouldFlyTo = options?.shouldFlyTo !== false;
  if (!viewer || viewer.isDestroyed()) return;

  const payload = extractMapPayloadFromSnapshot(snap);
  if (!payload.extent) return;

  if (payload.tileUrl) {
    const snapshotBoundaries = boundariesFromSnapshot(snap.boundaries);
    const boundaries = snapshotBoundaries.length ? snapshotBoundaries : boundariesFromGeoJson(payload.geojson);
    await loadRaster(
      payload.extent,
      payload.tileUrl,
      boundaries,
      viewer,
      imageryLayerRef,
      boundaryEntitiesRef,
      {
        shouldFlyTo,
        tileMaxZoom: payload.tileMaxZoom,
        tileMinZoom: payload.tileMinZoom,
        resetImagerySplit: true,
      },
    );
  }

  if (payload.geojson) {
    if (geoSourceRef) {
      viewer.dataSources.remove(geoSourceRef, true);
      geoSourceRef = null;
    }
    try {
      geoSourceRef = await GeoJsonDataSource.load(payload.geojson, {
        stroke: Color.CYAN,
        fill: Color.CYAN.withAlpha(0.12),
        strokeWidth: 2,
        clampToGround: true,
      });
      viewer.dataSources.add(geoSourceRef);
      if (!payload.tileUrl && shouldFlyTo && payload.extent) {
        flyToExtentNadir(viewer, payload.extent);
      }
    } catch {
      // GeoJSON 只是辅助边界，加载失败不影响栅格影像显示。
    }
  }
}

export function buildFinalAnswerFromSnapshot(snap: TaskRedisSnapshot): string {
  const warnings = Array.isArray(snap.warnings)
    ? snap.warnings.filter((w): w is string => typeof w === 'string' && w.trim().length > 0)
    : [];
  const appendWarnings = (text: string): string => {
    if (!warnings.length) return text;
    return `${text.trim()}\n\n## 质检提示\n\n${warnings.map((w) => `- ${w}`).join('\n')}`;
  };
  if (typeof snap.report_summary === 'string' && snap.report_summary.trim()) {
    return appendWarnings(snap.report_summary);
  }
  if (typeof snap.answer === 'string' && snap.answer.trim()) {
    return appendWarnings(snap.answer);
  }
  const st = snap.status ?? 'completed';
  const engineerMsg =
    (typeof snap.message === 'string' && snap.message.trim()) ||
    (typeof snap.engineer_output === 'string' && snap.engineer_output.trim()) ||
    (typeof snap.last_message === 'string' && snap.last_message.trim()) ||
    '';
  const err =
    (typeof snap.error === 'string' && snap.error.trim()) ||
    (typeof snap.error_message === 'string' && snap.error_message.trim()) ||
    '';
  if (st === 'engineer_failed' || st === 'completed_with_warnings' || st === 'failed') {
    const lines = [`## 分析未完全成功`, ``, `状态：\`${st}\``];
    if (engineerMsg) lines.push('', '**Engineer 说明**', engineerMsg);
    if (err) lines.push('', '**错误**', err);
    const m = snap.metrics as Record<string, unknown> | undefined;
    if (m && typeof m === 'object') {
      lines.push('', '**已有指标**', '```json', JSON.stringify(m, null, 2), '```');
    }
    lines.push(
      '',
      '常见原因：GEE 凭据/权限、earthengine-api、MCP gee(8101)、MinIO、TiTiler。请查看 agent-api 终端与 `/api/tasks/{id}/snapshot` 中的 `message`。',
    );
    return lines.join('\n');
  }
  return `分析结束（${st}）。`;
}
