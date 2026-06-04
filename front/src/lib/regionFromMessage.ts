/** 与 agent-api/tools/region_catalog.py 对齐的地名 → bbox 分析区域 */

export type Extent = { minLng: number; minLat: number; maxLng: number; maxLat: number };
export type RegionCoords = number[];

const NAMED_REGIONS: Record<string, [number, number, number, number]> = {
  太湖: [119.7, 30.85, 120.7, 31.65],
  巢湖: [117.55, 31.35, 118.05, 31.85],
  鄱阳湖: [115.8, 28.5, 117.0, 29.9],
  滇池: [102.55, 24.7, 102.85, 25.1],
  洪泽湖: [118.2, 33.1, 118.9, 33.6],
  南京: [118.4, 31.9, 119.1, 32.25],
  上海: [121.1, 30.9, 121.9, 31.5],
  杭州: [119.9, 30.0, 120.5, 30.5],
};

const TAIHU = NAMED_REGIONS['太湖'];

export function resolveRegionFromMessage(message: string): RegionCoords {
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

export function resolveAnalysisDatesFromMessage(message: string): {
  startDate: string;
  endDate: string;
  compareStartDate?: string;
  compareEndDate?: string;
} {
  const text = message.trim();
  const rangeMatch = text.match(
    /(20\d{2})\s*(?:年|-|\/|\.)\s*(1[0-2]|0?[1-9])\s*(?:月)?\s*(?:到|至|~|-)\s*(20\d{2})\s*(?:年|-|\/|\.)\s*(1[0-2]|0?[1-9])\s*(?:月)?/,
  );
  if (rangeMatch) {
    const y1 = Number(rangeMatch[1]);
    const m1 = Number(rangeMatch[2]);
    const y2 = Number(rangeMatch[3]);
    const m2 = Number(rangeMatch[4]);
    const fmt = (d: Date) => d.toISOString().slice(0, 10);
    return {
      startDate: fmt(new Date(Date.UTC(y1, m1 - 1, 1))),
      endDate: fmt(new Date(Date.UTC(y1, m1, 0))),
      compareStartDate: fmt(new Date(Date.UTC(y2, m2 - 1, 1))),
      compareEndDate: fmt(new Date(Date.UTC(y2, m2, 0))),
    };
  }

  const monthMatch = text.match(/(20\d{2})\s*(?:年|-|\/|\.)\s*(1[0-2]|0?[1-9])\s*(?:月)?/);
  if (monthMatch) {
    const year = Number(monthMatch[1]);
    const month = Number(monthMatch[2]);
    const start = new Date(Date.UTC(year, month - 1, 1));
    const end = new Date(Date.UTC(year, month, 0));
    const fmt = (d: Date) => d.toISOString().slice(0, 10);
    return { startDate: fmt(start), endDate: fmt(end) };
  }

  const yearMatch = text.match(/\b(20\d{2})\b|(?:^|[^\d])(20\d{2})年/);
  const yearRaw = yearMatch?.[1] || yearMatch?.[2];
  if (yearRaw) {
    const year = Number(yearRaw);
    return { startDate: `${year}-04-01`, endDate: `${year}-06-30` };
  }

  return defaultAnalysisDates();
}
