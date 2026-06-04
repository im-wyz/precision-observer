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
    if (path.includes('/cog/tiles/') && !parsed.searchParams.has('rescale')) {
      parsed.searchParams.set('rescale', '-0.5,0.9');
    }
    if (path.includes('/cog/tiles/') && !parsed.searchParams.has('colormap_name')) {
      parsed.searchParams.set('colormap_name', 'viridis');
    }
    const templatePath = decodeURIComponent(parsed.pathname);
    const styled = `${parsed.protocol}//${parsed.host}${templatePath}${parsed.search}`;
    if (dev && useProxy && typeof window !== 'undefined' && templatePath.includes('/cog/') && !templatePath.includes(proxyPrefix)) {
      return `${window.location.origin}${proxyPrefix}${templatePath}${parsed.search}`;
    }
    if (titilerPublicBase) {
      const want = new URL(titilerPublicBase);
      return styled.replace(`${parsed.protocol}//${parsed.host}`, `${want.protocol}//${want.host}`);
    }
    return styled;
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

export function buildTitilerTileTemplate(cogHttpUrl: string): string {
  const base = getTitilerBase();
  const encoded = encodeURIComponent(cogHttpUrl);
  return `${base}/cog/tiles/WebMercatorQuad/{z}/{x}/{y}?url=${encoded}&rescale=-0.5,0.9&colormap_name=viridis`;
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
  const meta = snap.meta as Record<string, unknown> | undefined;
  const extent =
    extentFromObject(snap.extent) ??
    extentFromObject(meta?.extent) ??
    coordsToExtent(snap.region_coords) ?? {
    minLng: 119.7,
    minLat: 30.85,
    maxLng: 120.7,
    maxLat: 31.65,
  };
  const metrics = snap.metrics as Record<string, unknown> | undefined;
  const simulated = metrics?.simulated === true || (meta && meta.simulated === true);

  return {
    tileUrl: tileUrl && !simulated && !isNonTitilerCogUrl(tileUrl) ? tileUrl : null,
    extent,
    geojson: extractGeoJsonFromSnapshot(snap),
    tileMaxZoom: typeof snap.tile_max_zoom === 'number' ? snap.tile_max_zoom : undefined,
    tileMinZoom: typeof snap.tile_min_zoom === 'number' ? snap.tile_min_zoom : undefined,
  };
}

function extentFromObject(value: unknown): Extent | null {
  if (!value || typeof value !== 'object') return null;
  const o = value as Record<string, unknown>;
  const minLng = Number(o.minLng ?? o.min_lng);
  const minLat = Number(o.minLat ?? o.min_lat);
  const maxLng = Number(o.maxLng ?? o.max_lng);
  const maxLat = Number(o.maxLat ?? o.max_lat);
  if ([minLng, minLat, maxLng, maxLat].every(Number.isFinite)) {
    return { minLng, minLat, maxLng, maxLat };
  }
  return null;
}
