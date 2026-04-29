import { useEffect, useRef, useState } from 'react';
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
import { Cartesian3, Color, Entity, ImageryLayer, Ion, Math as CesiumMath, PolygonHierarchy, Rectangle, UrlTemplateImageryProvider, Viewer } from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';

type Extent = { minLng: number; minLat: number; maxLng: number; maxLat: number };
type TitilerTileJson = {
  tiles?: string[];
  bounds?: [number, number, number, number];
  minzoom?: number;
  maxzoom?: number;
};
type ChatMessage = { role: 'system' | 'user' | 'assistant'; text: string };
type MonthSelection = { year: number; month: number };
type ParsedIntent = { place?: string; monthSelection?: MonthSelection };
type LiveImageryResponse = {
  query: string;
  displayName: string;
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
  token: string;
  tileTemplateUrl: string;
  boundaries: Array<Array<{ lng: number; lat: number }>>;
};
type ApiErrorResponse = {
  status?: number;
  error?: string;
  message?: string;
  path?: string;
  timestamp?: string;
};

const DEFAULT_EXTENT: Extent = { minLng: -150, minLat: 40, maxLng: -50, maxLat: 70 };

export default function AIWorkspace() {
  const cesiumContainerRef = useRef<HTMLDivElement | null>(null);
  const viewerRef = useRef<Viewer | null>(null);
  const imageryLayerRef = useRef<ImageryLayer | null>(null);
  const boundaryMaskEntitiesRef = useRef<Entity[]>([]);
  const requestAbortRef = useRef<AbortController | null>(null);
  const [chatInput, setChatInput] = useState('');
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([
    {
      role: 'system',
      text: '请输入地名和月份（例如：南京6月影像、2024年6月南京影像）。也可以先说“南京影像”，我会追问你要几月。',
    },
  ]);
  const [pendingPlace, setPendingPlace] = useState<string | null>(null);

  const rasterApiBase =
    (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_RASTER_API_URL ||
    'http://localhost:8080';
  const titilerBase =
    (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_TITILER_URL ||
    'http://localhost:8000';

  function extractPlaceQuery(text: string): string {
    const cleaned = text
      .trim()
      .replace(/[，。,.!?！？]/g, ' ')
      .replace(/(显示|切换到|切到|打开|调取|查询|加载|帮我|请|遥感影像|影像|地图|区域|范围|城市|月份|月|年|我要|想要|给我|看一下|看下|帮忙)/g, ' ')
      .replace(/的/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (!cleaned) return '';
    // 常见中文输入保留首个实体片段，避免“南京 的”传给后端导致边界检索失败
    return cleaned.split(' ')[0]?.trim() || '';
  }

  function parseMonthSelection(text: string): MonthSelection | undefined {
    const normalized = text.replace(/\s+/g, '');
    const match = normalized.match(/(?:(20\d{2})年)?(1[0-2]|0?[1-9])月/);
    if (!match) return undefined;
    const nowYear = new Date().getFullYear();
    const year = match[1] ? Number(match[1]) : nowYear;
    const month = Number(match[2]);
    if (!Number.isFinite(year) || !Number.isFinite(month) || month < 1 || month > 12) return undefined;
    return { year, month };
  }

  function parseIntent(text: string): ParsedIntent {
    const monthSelection = parseMonthSelection(text);
    const place = extractPlaceQuery(
      monthSelection
        ? text.replace(/(?:(20\d{2})\s*年)?\s*(1[0-2]|0?[1-9])\s*月/g, ' ')
        : text,
    );
    const intent: ParsedIntent = {};
    if (place) intent.place = place;
    if (monthSelection) intent.monthSelection = monthSelection;
    return intent;
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

  async function loadRasterByCog(
    extent: Extent,
    tileTemplateUrl: string,
    boundaries: Array<Array<{ lng: number; lat: number }>>,
  ) {
    const viewer = viewerRef.current;
    if (!viewer) return;

    requestAbortRef.current?.abort();
    const abortController = new AbortController();
    requestAbortRef.current = abortController;

    const targetRectangle = Rectangle.fromDegrees(extent.minLng, extent.minLat, extent.maxLng, extent.maxLat);
    let layerRectangle = targetRectangle;
    let minimumLevel = 0;
    let maximumLevel = 16;

    const provider = new UrlTemplateImageryProvider({
      url: tileTemplateUrl,
      // 以地名解析得到的区域框作为显示裁切边界
      rectangle: targetRectangle,
      tileWidth: 512,
      tileHeight: 512,
      minimumLevel,
      maximumLevel,
      enablePickFeatures: false,
    });

    provider.errorEvent.addEventListener((tileError) => {
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

    clearBoundaryMask(viewer);
    applyBoundaryMask(viewer, boundaries, targetRectangle);

    imageryLayerRef.current = viewer.imageryLayers.add(new ImageryLayer(provider));
    // 视角始终以地名解析出来的城市范围为准，避免因单景影像 footprint 偏移导致“飞偏”
    viewer.camera.flyTo({ destination: targetRectangle, duration: 1.5 });
  }

  function clearBoundaryMask(viewer: Viewer) {
    for (const entity of boundaryMaskEntitiesRef.current) {
      viewer.entities.remove(entity);
    }
    boundaryMaskEntitiesRef.current = [];
  }

  function applyBoundaryMask(
    viewer: Viewer,
    boundaries: Array<Array<{ lng: number; lat: number }>>,
    fallbackRectangle: Rectangle,
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
        material: Color.BLACK.withAlpha(0.38),
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

  async function handleSendMessage() {
    const text = chatInput.trim();
    if (!text) return;

    setChatInput('');
    setChatMessages((prev) => [...prev, { role: 'user', text }]);

    try {
      const intent = parseIntent(text);
      const effectivePlace = intent.place || pendingPlace || '';
      const effectiveMonth = intent.monthSelection;

      if (!effectivePlace) {
        setChatMessages((prev) => [
          ...prev,
          { role: 'assistant', text: '请先告诉我地名，例如“南京影像”。' },
        ]);
        return;
      }

      if (!effectiveMonth) {
        setPendingPlace(effectivePlace);
        setChatMessages((prev) => [
          ...prev,
          { role: 'assistant', text: `你要 ${effectivePlace} 的几月影像？例如“6月”或“2024年6月”。` },
        ]);
        return;
      }

      const live = await queryLiveImagery(effectivePlace, effectiveMonth);
      await loadRasterByCog(live.extent, live.tileTemplateUrl, live.boundaries);
      setPendingPlace(null);
      setChatMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: `已定位到 ${live.displayName}，并加载 ${effectiveMonth.year}年${effectiveMonth.month}月 的遥感影像。`,
        },
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
    if (!cesiumContainerRef.current || viewerRef.current) return;

    Ion.defaultAccessToken =
      'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiI5MzUzYzQ3YS04ODA5LTQ4NjYtYTE3YS02ZmVlMGE5YzM5YWQiLCJpZCI6NDIxMjQ0LCJpYXQiOjE3NzY3Njk4NjB9.Upl_FpBGk7HAZdk6m_WK8wc-PJXGHVPqjGAUoBbJZV0';

    const viewer = new Viewer(cesiumContainerRef.current, {
      animation: false,
      baseLayerPicker: true,
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

    viewer.camera.flyTo({
      destination: Cartesian3.fromDegrees(116.4074, 39.9042, 1200000),
      duration: 1.0,
    });

    viewerRef.current = viewer;

    // 首屏不再依赖本地数据库；等待用户输入地名后实时检索加载

    return () => {
      requestAbortRef.current?.abort();
      requestAbortRef.current = null;
      if (viewerRef.current && imageryLayerRef.current) {
        try {
          viewerRef.current.imageryLayers.remove(imageryLayerRef.current, true);
        } catch {
          // ignore cleanup errors
        }
        imageryLayerRef.current = null;
      }
      if (viewerRef.current) {
        clearBoundaryMask(viewerRef.current);
      }
      viewerRef.current?.destroy();
      viewerRef.current = null;
    };
  }, []);

  return (
    <div className="flex flex-row-reverse h-full w-full overflow-hidden bg-[#f8f9fa] text-slate-800">
      <section className="w-[40%] flex flex-col border-l border-slate-200 bg-white">
        <div className="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50">
          <h2 className="text-sm font-bold font-headline">Analysis Stream</h2>
          <span className="text-[10px] px-2 py-0.5 bg-slate-200 text-slate-600 font-bold rounded uppercase">MODEL: GPT-4O-GEO</span>
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
                  {message.text}
                </div>
              </div>
            );
          })}
        </div>

        <div className="p-4 border-t border-slate-100">
          <div className="relative bg-white rounded-lg border border-slate-200 overflow-hidden focus-within:ring-2 focus-within:ring-indigo-500 transition-all shadow-sm">
            <textarea
              className="w-full bg-transparent border-none focus:ring-0 text-xs p-3 h-20 resize-none placeholder:text-slate-400 custom-scrollbar"
              placeholder="输入城市名，例如：北京 / 上海 / 南京..."
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void handleSendMessage();
                }
              }}
            />
            <div className="flex items-center justify-between px-3 py-2 bg-slate-50 border-t border-slate-100">
              <div className="flex gap-2">
                <button className="text-slate-400 hover:text-indigo-500 transition-colors"><ImageIcon className="w-3.5 h-3.5" /></button>
                <button className="text-slate-400 hover:text-indigo-500 transition-colors"><RectangleHorizontal className="w-3.5 h-3.5" /></button>
                <button className="text-slate-400 hover:text-indigo-500 transition-colors"><Paperclip className="w-3.5 h-3.5" /></button>
              </div>
              <button
                className="bg-indigo-600 text-white p-1.5 rounded hover:bg-indigo-500 active:scale-95 transition-all shadow-sm disabled:bg-slate-300 disabled:cursor-not-allowed"
                onClick={() => void handleSendMessage()}
                disabled={!chatInput.trim()}
              >
                <Send className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>
      </section>

      <section className="flex-1 relative bg-slate-100 overflow-hidden">
        <div className="absolute inset-0 z-0">
          <div ref={cesiumContainerRef} className="h-full w-full" />
        </div>

        <motion.div
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
        </motion.div>

        <div className="absolute right-4 top-15 flex flex-col gap-2">
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
        </div>

        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-48 h-48 border border-indigo-500/30 rounded pointer-events-none">
          <div className="absolute -top-1 -left-1 w-3 h-3 border-t-2 border-l-2 border-indigo-500" />
          <div className="absolute -top-1 -right-1 w-3 h-3 border-t-2 border-r-2 border-indigo-500" />
          <div className="absolute -bottom-1 -left-1 w-3 h-3 border-b-2 border-l-2 border-indigo-500" />
          <div className="absolute -bottom-1 -right-1 w-3 h-3 border-b-2 border-r-2 border-indigo-500" />
        </div>
      </section>
    </div>
  );
}
