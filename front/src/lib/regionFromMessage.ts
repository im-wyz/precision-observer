/** 与 agent-api/tools/region_catalog.py 对齐的地名 → bbox */

export type Extent = { minLng: number; minLat: number; maxLng: number; maxLat: number };

const NAMED_REGIONS: Record<string, [number, number, number, number]> = {
  太湖: [119.8, 30.95, 120.55, 31.55],
  巢湖: [117.55, 31.35, 118.05, 31.85],
  鄱阳湖: [115.8, 28.5, 117.0, 29.9],
  滇池: [102.55, 24.7, 102.85, 25.1],
  洪泽湖: [118.2, 33.1, 118.9, 33.6],
  南京: [118.4, 31.9, 119.1, 32.25],
  上海: [121.1, 30.9, 121.9, 31.5],
  杭州: [119.9, 30.0, 120.5, 30.5],
};

const TAIHU: [number, number, number, number] = NAMED_REGIONS['太湖'];

export function resolveRegionFromMessage(message: string): number[] {
  const text = message.trim();
  for (const [name, bbox] of Object.entries(NAMED_REGIONS)) {
    if (text.includes(name)) {
      return [...bbox];
    }
  }
  if (/湖|藻|水体|水域|水库/.test(text)) {
    return [...TAIHU];
  }
  return [...TAIHU];
}

export function detectAnalysisTypeLabel(message: string): string {
  if (/蓝藻|藻华|水华|藻类|浮游|cyanobacteria|algal|bloom/i.test(message)) {
    return '蓝藻/藻华监测';
  }
  if (/ndci/i.test(message) || /归一化差异/.test(message)) {
    return 'NDCI 分析';
  }
  if (/水体|水域|水质|富营养/.test(message)) {
    return '水体监测';
  }
  return '综合遥感分析';
}

/** 默认用「最近 90 天」，避免固定 6–8 月在未来年份无 Sentinel-2 影像 */
export function defaultAnalysisDates(): { startDate: string; endDate: string } {
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - 90);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  return { startDate: fmt(start), endDate: fmt(end) };
}
