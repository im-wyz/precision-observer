import { Search, Calendar, Target, Satellite, Sliders, Download, MoreVertical, Layers, Sparkles, PlusCircle, UploadCloud, Box, MessageSquare, Loader2, Trash2 } from 'lucide-react';
import { motion } from 'motion/react';
import { useEffect, useState } from 'react';

type ResourceLibraryProps = {
  onOpenWorkspaceSession: (sessionId: string) => void;
  onCreateWorkspace?: () => void;
};

type WorkspaceSessionListRow = { id: string; title: string; updatedAt: string | null };

const WORKSPACE_SESSION_STORAGE_KEY = 'po_active_workspace_session_id';

const rasterApiBase = (
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_RASTER_API_URL ?? 'http://localhost:8080'
).replace(/\/$/, '');

function formatLocalTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return String(iso);
  }
}

function cardAccent(id: string): 'secondary' | 'primary' | 'tertiary' {
  const n = id.split('').reduce((a, c) => a + c.charCodeAt(0), 0);
  return n % 3 === 0 ? 'secondary' : n % 3 === 1 ? 'primary' : 'tertiary';
}

export default function ResourceLibrary({ onOpenWorkspaceSession, onCreateWorkspace }: ResourceLibraryProps) {
  const [sessions, setSessions] = useState<WorkspaceSessionListRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`${rasterApiBase}/api/workspace-sessions`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = (await res.json()) as { sessions?: WorkspaceSessionListRow[] };
        if (!cancelled) setSessions(Array.isArray(data.sessions) ? data.sessions : []);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!menuOpenId) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const el = e.target as HTMLElement | null;
      if (el?.closest('[data-session-card-menu]')) return;
      setMenuOpenId(null);
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [menuOpenId]);

  async function handleDeleteSession(session: WorkspaceSessionListRow) {
    const label = session.title?.trim() || '未命名工作区';
    if (!window.confirm(`确定删除「${label}」？将从数据库永久移除，且无法恢复。`)) return;

    setMenuOpenId(null);
    setDeletingId(session.id);
    setError(null);
    try {
      const res = await fetch(`${rasterApiBase}/api/workspace-sessions/${encodeURIComponent(session.id)}`, {
        method: 'DELETE',
      });
      if (!res.ok) {
        let msg = `HTTP ${res.status}`;
        try {
          const err = (await res.json()) as { message?: string };
          if (err.message) msg = err.message;
        } catch {
          // ignore
        }
        throw new Error(msg);
      }
      setSessions((prev) => prev.filter((s) => s.id !== session.id));
      const active = sessionStorage.getItem(WORKSPACE_SESSION_STORAGE_KEY);
      if (active === session.id) {
        sessionStorage.removeItem(WORKSPACE_SESSION_STORAGE_KEY);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="h-full flex flex-col bg-[#f8f9fa] p-8 overflow-hidden">
      <header className="mb-8">
        <div className="flex justify-between items-end mb-6">
          <div>
            <h1 className="text-3xl font-headline font-bold text-slate-800 tracking-tight mb-1">Resource Library</h1>
            <p className="text-slate-500 font-sans text-[10px] font-bold uppercase tracking-widest opacity-80">
              已保存的 AI 工作区（对话 + 地图状态）
            </p>
          </div>
          <div className="hidden xl:flex gap-4">
            <div className="px-4 py-2 bg-white rounded-lg flex items-center gap-3 border border-slate-200 shadow-sm h-fit">
              <Box className="w-5 h-5 text-indigo-500" />
              <div>
                <div className="text-[9px] text-slate-400 uppercase font-bold leading-none tracking-wider">Sessions</div>
                <div className="text-sm font-headline font-bold text-slate-800">
                  {loading ? '…' : `${sessions.length} 条`}
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-12 gap-3">
          <div className="col-span-4 relative group">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 w-4 h-4 group-focus-within:text-indigo-500 transition-colors" />
            <input
              className="w-full bg-white border border-slate-200 focus:ring-2 focus:ring-indigo-500 rounded-md pl-9 pr-4 py-2 text-xs font-sans placeholder:text-slate-400 transition-all outline-none shadow-sm"
              placeholder="搜索（前端筛选，后续可接服务端）…"
              type="text"
              disabled
            />
          </div>
          <div className="col-span-2">
            <button
              type="button"
              disabled
              className="w-full bg-white opacity-60 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-not-allowed transition-colors group shadow-sm"
            >
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">Time Range</span>
              <Calendar className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2">
            <button
              type="button"
              disabled
              className="w-full bg-white opacity-60 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-not-allowed transition-colors group shadow-sm"
            >
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">BBox Search</span>
              <Target className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2">
            <button
              type="button"
              disabled
              className="w-full bg-white opacity-60 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-not-allowed transition-colors group shadow-sm"
            >
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">Source</span>
              <Satellite className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2 flex gap-2">
            <button
              type="button"
              disabled
              className="flex-1 bg-white opacity-60 border border-slate-200 rounded-md flex items-center justify-center cursor-not-allowed transition-all shadow-sm"
            >
              <Sliders className="w-4 h-4" />
            </button>
            <button
              type="button"
              disabled
              className="flex-1 bg-white opacity-60 border border-slate-200 rounded-md flex items-center justify-center cursor-not-allowed transition-all shadow-sm"
            >
              <Download className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto custom-scrollbar pr-2">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-16 text-slate-500 text-sm">
            <Loader2 className="w-5 h-5 animate-spin" />
            正在加载会话列表…
          </div>
        ) : error ? (
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-sm text-amber-900 max-w-2xl">
            <p className="font-bold mb-2">无法读取会话列表（{error}）</p>
            <p className="mb-2">请确认后端已启动；首次连接数据库时 Flyway 会自动创建 workspace_session 表。</p>
          </div>
        ) : sessions.length === 0 ? (
          <div className="flex flex-col gap-6 max-w-2xl">
            <div className="rounded-xl border border-slate-200 bg-white p-8 text-slate-600 text-sm shadow-sm">
              <p className="font-bold text-slate-800 mb-2">暂无已保存工作区</p>
              <p className="mb-4">在「Workspace」里对话、上传影像或运行分析后，会自动保存到数据库。</p>
              <button
                type="button"
                onClick={() => onCreateWorkspace?.()}
                className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-xs font-bold text-white hover:bg-indigo-500 transition-colors"
              >
                <PlusCircle className="w-4 h-4" />
                新建工作区并进入对话
              </button>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {sessions.map((session, index) => {
              const color = cardAccent(session.id);
              const menuOpen = menuOpenId === session.id;
              const isDeleting = deletingId === session.id;
              return (
                <motion.div
                  key={session.id}
                  initial={{ opacity: 0, scale: 0.98 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ delay: index * 0.04 }}
                  role="button"
                  tabIndex={0}
                  onClick={() => {
                    if (isDeleting) return;
                    onOpenWorkspaceSession(session.id);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      if (!isDeleting) onOpenWorkspaceSession(session.id);
                    }
                  }}
                  className={`group relative flex flex-col text-left bg-white rounded-xl overflow-visible shadow-sm hover:shadow-md border border-slate-200 transition-all duration-300 hover:translate-y-[-2px] cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${isDeleting ? 'opacity-60 pointer-events-none' : ''}`}
                >
                  <motion.div className="aspect-[16/10] overflow-hidden relative bg-gradient-to-br from-slate-100 to-indigo-50 flex items-center justify-center rounded-t-xl" layout={false}>
                    <MessageSquare className="w-14 h-14 text-indigo-200 group-hover:text-indigo-300 transition-colors" />
                    <div
                      className={`absolute top-2 left-2 px-2 py-0.5 rounded text-[9px] font-bold tracking-wider flex items-center gap-1.5 border backdrop-blur-md
                  ${
                    color === 'secondary'
                      ? 'bg-emerald-50/80 text-emerald-600 border-emerald-200'
                      : color === 'primary'
                        ? 'bg-indigo-50/80 text-indigo-600 border-indigo-200'
                        : 'bg-amber-50/80 text-amber-600 border-amber-200'
                  }`}
                    >
                      <div
                        className={`w-1.5 h-1.5 rounded-full ${
                          color === 'secondary' ? 'bg-emerald-500' : color === 'primary' ? 'bg-indigo-500' : 'bg-amber-500'
                        }`}
                      />
                      SAVED
                    </div>
                  </motion.div>
                  <motion.div className="p-4 flex flex-col gap-3" layout={false}>
                    <div className="flex justify-between items-start gap-2">
                      <div className="min-w-0">
                        <h3 className="font-headline font-bold text-slate-800 text-sm line-clamp-2">{session.title}</h3>
                        <p className="text-[10px] font-mono text-slate-400 uppercase tracking-widest mt-0.5 opacity-80 truncate">
                          {session.id}
                        </p>
                      </div>
                      <div className="relative shrink-0" data-session-card-menu>
                        <button
                          type="button"
                          aria-label="更多操作"
                          aria-expanded={menuOpen}
                          disabled={isDeleting}
                          onClick={(e) => {
                            e.stopPropagation();
                            setMenuOpenId((prev) => (prev === session.id ? null : session.id));
                          }}
                          className={`p-1 rounded-md transition-colors ${
                            menuOpen ? 'text-indigo-600 bg-indigo-50' : 'text-slate-300 group-hover:text-indigo-600 hover:bg-slate-50'
                          }`}
                        >
                          {isDeleting ? <Loader2 className="w-4 h-4 animate-spin" /> : <MoreVertical className="w-4 h-4" />}
                        </button>
                        {menuOpen && (
                          <motion.div
                            className="absolute right-0 top-full mt-1 z-30 min-w-[7.5rem] rounded-lg border border-slate-200 bg-white py-1 shadow-lg"
                            onClick={(e) => e.stopPropagation()}
                            layout={false}
                          >
                            <button
                              type="button"
                              className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs font-bold text-red-600 hover:bg-red-50 transition-colors"
                              onClick={(e) => {
                                e.stopPropagation();
                                void handleDeleteSession(session);
                              }}
                            >
                              <Trash2 className="w-3.5 h-3.5 shrink-0" />
                              删除
                            </button>
                          </motion.div>
                        )}
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-y-2 border-t border-slate-50 pt-3">
                      <div>
                        <div className="text-[8px] uppercase tracking-wider text-slate-400 font-bold">类型</div>
                        <div className="text-[11px] font-bold text-slate-700">AI Workspace</div>
                      </div>
                      <div>
                        <div className="text-[8px] uppercase tracking-wider text-slate-400 font-bold">最近更新</div>
                        <div className="text-[11px] font-bold text-slate-700 leading-snug">{formatLocalTime(session.updatedAt)}</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 pt-1">
                      <div className="text-[9px] font-bold text-indigo-600 bg-indigo-50 px-1.5 py-0.5 rounded">点击恢复</div>
                      <div className="ml-auto flex gap-1 items-center">
                        <Layers className="w-3.5 h-3.5 text-slate-300" />
                        <Sparkles className="w-3.5 h-3.5 text-slate-300" />
                      </div>
                    </div>
                  </motion.div>
                </motion.div>
              );
            })}

            <motion.button
              type="button"
              onClick={() => onCreateWorkspace?.()}
              className="flex flex-col items-center justify-center border-2 border-dashed border-slate-200 rounded-xl p-8 hover:border-indigo-400 transition-all group cursor-pointer bg-slate-50/50 text-left w-full"
            >
              <div className="w-12 h-12 rounded-full bg-white flex items-center justify-center mb-3 group-hover:scale-110 transition-transform shadow-sm border border-slate-200">
                <PlusCircle className="w-6 h-6 text-indigo-500" />
              </div>
              <div className="text-xs font-bold text-slate-600">新建工作区</div>
              <div className="text-[9px] font-bold text-slate-400 uppercase tracking-widest mt-1 text-center px-2">
                打开空白 Workspace，开始新的对话与地图分析
              </div>
            </motion.button>
          </div>
        )}
      </div>

      <button
        type="button"
        className="fixed bottom-6 right-6 w-12 h-12 bg-indigo-600 text-white rounded-full shadow-lg flex items-center justify-center hover:scale-110 active:scale-95 transition-transform z-50 group"
        aria-label="Upload"
      >
        <UploadCloud className="w-6 h-6" />
      </button>

      <div className="fixed top-24 right-8 w-72 glass-panel rounded-xl p-6 border border-outline/10 hidden xl:block shadow-2xl">
        <h4 className="text-[10px] font-headline font-bold text-primary tracking-widest uppercase mb-4">Workspace 会话</h4>
        <div className="aspect-square bg-zinc-950 rounded-lg mb-4 overflow-hidden relative border border-outline/20 flex items-center justify-center">
          <Box className="w-16 h-16 text-zinc-700" />
          <div className="absolute inset-0 bg-primary/5" />
        </div>
        <div className="space-y-4">
          <div>
            <div className="text-[9px] text-on-surface-variant font-bold uppercase tracking-widest opacity-50">说明</div>
            <div className="text-[11px] text-secondary mt-1 leading-relaxed">
              卡片数据来自后端 PostgreSQL。点击卡片会回到 Workspace 并恢复对话与地图（耕地卷帘 / 本地上传 / Roboflow 叠加）。
            </div>
          </div>
          <div>
            <div className="text-[9px] text-on-surface-variant font-bold uppercase tracking-widest opacity-50">已列出</div>
            <div className="text-xl font-headline font-bold text-on-surface">{sessions.length}</div>
          </div>
        </div>
      </div>
    </div>
  );
}
