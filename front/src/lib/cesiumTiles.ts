/** TiTiler 瓦片 URL 与任务结果解析（供 Cesium UrlTemplateImageryProvider 使用） */

import type { TaskRedisSnapshot } from '../api/tasks';

export type Extent = { minLng: number; minLat: number; maxLng: number; maxLat: number };

export function rewriteTileTemplateUrlForBrowser(template: string): string {
  const trimmed = template.trim();
  if (!trimmed) return template;

  const envObj = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  const dev = Boolean(envObj?.DEV);
  const useProxy = envObj?.VITE_TITILER_USE_PROXY !== 'false';
  const proxyPrefix = (envObj?.VITE_TITILER_PROXY_PREFIX || '/titiler-proxy').replace(/\/$/, '') || '/titiler-proxy';
  const titilerPublicBase = (envObj?.VITE_TITILER_URL || '').replace(/\/$/, '');

  // 避免 loadRasterByCog 与 extractMapPayload 多次调用导致 /titiler-proxy 重复拼接
  if (trimmed.includes(`${proxyPrefix}/`) || trimmed.includes(`${proxyPrefix}?`)) {
    return trimmed;
  }

  try {
    const parsed = new URL(trimmed);
    const path = parsed.pathname;
    if (dev && useProxy && typeof window !== 'undefined' && path.includes('/cog/') && !path.includes(proxyPrefix)) {
      return `${window.location.origin}${proxyPrefix}${path}${parsed.search}`;
    }
    if (titilerPublicBase) {
      const want = new URL(titilerPublicBase);
      return template.replace(`${parsed.protocol}//${parsed.host}`, `${want.protocol}//${want.host}`);
    }
  } catch {
    return template;
  }
  return template;
}

export function getTitilerBase(): string {
  const envObj = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  const raw = (envObj?.VITE_TITILER_URL || 'http://localhost:8000').replace(/\/$/, '');
  return raw;
}

/** 由 COG HTTP URL 构造 TiTiler WebMercatorQuad 瓦片模板 */
export function buildTitilerTileTemplate(cogHttpUrl: string): string {
  const base = getTitilerBase();
  const encoded = encodeURIComponent(cogHttpUrl);
  return `${base}/cog/tiles/WebMercatorQuad/{z}/{x}/{y}?url=${encoded}`;
}

export function extractTileUrlFromSnapshot(snap: TaskRedisSnapshot): string | null {
  const direct =
    snap.tile_url ||
    snap.tileUrl ||
    snap.tile_template_url ||
    snap.tileTemplateUrl;
  if (typeof direct === 'string' && direct.includes('{z}')) {
    return direct;
  }
  const cog = snap.download_url || snap.cog_path;
  if (typeof cog === 'string' && /^https?:\/\//i.test(cog)) {
    if (isNonTitilerCogUrl(cog)) {
      return null;
    }
    return buildTitilerTileTemplate(cog);
  }
  return null;
}

/** 演示/mock 或 TiTiler 无法拉取的地址，不生成瓦片模板 */
export function isNonTitilerCogUrl(url: string): boolean {
  const u = url.toLowerCase();
  return u.includes('mock-cog') || u.includes('/mock/') || u.includes('?region=bbox_');
}

export function coordsToExtent(coords: unknown): Extent | null {
  if (!Array.isArray(coords) || coords.length === 0) {
    return null;
  }
  const flat = coords as unknown[];
  if (flat.length >= 4 && flat.every((x) => typeof x === 'number')) {
    const [a, b, c, d] = flat as number[];
    return {
      minLng: Math.min(a, c),
      minLat: Math.min(b, d),
      maxLng: Math.max(a, c),
      maxLat: Math.max(b, d),
    };
  }
  const pts: Array<{ lng: number; lat: number }> = [];
  for (const item of flat) {
    if (Array.isArray(item) && item.length >= 2) {
      pts.push({ lng: Number(item[0]), lat: Number(item[1]) });
    } else if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>;
      if (typeof o.lng === 'number' && typeof o.lat === 'number') {
        pts.push({ lng: o.lng, lat: o.lat });
      }
    }
  }
  if (!pts.length) return null;
  let minLng = pts[0].lng;
  let maxLng = pts[0].lng;
  let minLat = pts[0].lat;
  let maxLat = pts[0].lat;
  for (const p of pts) {
    minLng = Math.min(minLng, p.lng);
    maxLng = Math.max(maxLng, p.lng);
    minLat = Math.min(minLat, p.lat);
    maxLat = Math.max(maxLat, p.lat);
  }
  return { minLng, minLat, maxLng, maxLat };
}

export function extractGeoJsonFromSnapshot(snap: TaskRedisSnapshot): Record<string, unknown> | null {
  const g = snap.geojson || snap.vector_boundary;
  if (g && typeof g === 'object') {
    return g as Record<string, unknown>;
  }
  const coords = snap.region_coords;
  const extent = coordsToExtent(coords);
  if (!extent) return null;
  return {
    type: 'Feature',
    properties: { name: 'analysis_region' },
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [extent.minLng, extent.minLat],
          [extent.maxLng, extent.minLat],
          [extent.maxLng, extent.maxLat],
          [extent.minLng, extent.maxLat],
          [extent.minLng, extent.minLat],
        ],
      ],
    },
  };
}

export type TaskMapPayload = {
  tileUrl: string | null;
  extent: Extent | null;
  geojson: Record<string, unknown> | null;
  tileMaxZoom?: number;
  tileMinZoom?: number;
};

export function extractMapPayloadFromSnapshot(snap: TaskRedisSnapshot): TaskMapPayload {
  const tileUrl = extractTileUrlFromSnapshot(snap);
  const extent = coordsToExtent(snap.region_coords) ?? {
    minLng: 119.8,
    minLat: 31.0,
    maxLng: 120.6,
    maxLat: 31.6,
  };
  const metrics = snap.metrics as Record<string, unknown> | undefined;
  const simulated = metrics?.simulated === true || snap.meta && (snap.meta as Record<string, unknown>).simulated === true;

  return {
    tileUrl: tileUrl && !simulated && !isNonTitilerCogUrl(tileUrl) ? tileUrl : null,
    extent,
    geojson: extractGeoJsonFromSnapshot(snap),
    tileMaxZoom: typeof snap.tile_max_zoom === 'number' ? snap.tile_max_zoom : undefined,
    tileMinZoom: typeof snap.tile_min_zoom === 'number' ? snap.tile_min_zoom : undefined,
  };
}
