/** Spring Boot 遥感分析任务 API（POST /api/tasks + STOMP /topic/task/{id}） */

export type TaskCreatePayload = {
  message: string;
  regionCoords?: Array<number | [number, number]>;
  startDate: string;
  endDate: string;
};

export type TaskResponse = {
  id: string;
  message: string;
  regionCoords?: unknown[];
  startDate: string;
  endDate: string;
  status: string;
  answer?: string | null;
  cogPath?: string | null;
  downloadUrl?: string | null;
  errorMessage?: string | null;
  currentNode?: string | null;
  lastMessage?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

/** Redis / STOMP 推送的完整任务快照 */
export type TaskRedisSnapshot = {
  task_id?: string;
  status?: string;
  user_message?: string;
  region_coords?: unknown[];
  start_date?: string;
  end_date?: string;
  answer?: string;
  cog_path?: string;
  download_url?: string;
  tile_url?: string;
  tileUrl?: string;
  tile_template_url?: string;
  tileTemplateUrl?: string;
  tile_max_zoom?: number;
  tile_min_zoom?: number;
  geojson?: Record<string, unknown>;
  vector_boundary?: Record<string, unknown>;
  boundaries?: unknown;
  current_node?: string;
  last_message?: string;
  progress?: Array<{ node?: string; message?: string; [key: string]: unknown }>;
  error?: string;
  analysis_intent?: string;
  analysis_type?: string;
  report_title?: string;
  report_summary?: string;
  metrics?: Record<string, string | number | boolean>;
  [key: string]: unknown;
};

export function getRasterApiBase(): string {
  const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  return (env?.VITE_RASTER_API_URL || 'http://localhost:8080').replace(/\/$/, '');
}

export function getWsBaseUrl(): string {
  const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  if (env?.VITE_WS_URL) {
    return env.VITE_WS_URL.replace(/\/$/, '');
  }
  if (typeof window !== 'undefined' && env?.DEV && env?.VITE_WS_USE_PROXY !== 'false') {
    return window.location.origin;
  }
  return getRasterApiBase();
}

export async function createAnalysisTask(
  baseUrl: string,
  payload: TaskCreatePayload,
): Promise<TaskResponse> {
  const res = await fetch(`${baseUrl.replace(/\/$/, '')}/api/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: payload.message,
      regionCoords: payload.regionCoords ?? [],
      startDate: payload.startDate,
      endDate: payload.endDate,
    }),
  });
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(`创建任务失败 ${res.status}: ${raw.slice(0, 500)}`);
  }
  return (await res.json()) as TaskResponse;
}

export async function getAnalysisTask(baseUrl: string, taskId: string): Promise<TaskResponse> {
  const res = await fetch(`${baseUrl.replace(/\/$/, '')}/api/tasks/${encodeURIComponent(taskId)}`);
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(`查询任务失败 ${res.status}: ${raw.slice(0, 500)}`);
  }
  return (await res.json()) as TaskResponse;
}

/** Redis 全量快照（与 STOMP 推送体一致）；WebSocket 不可用时轮询此接口 */
export async function getTaskSnapshot(baseUrl: string, taskId: string): Promise<TaskRedisSnapshot> {
  const res = await fetch(
    `${baseUrl.replace(/\/$/, '')}/api/tasks/${encodeURIComponent(taskId)}/snapshot`,
  );
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(`查询任务快照失败 ${res.status}: ${raw.slice(0, 500)}`);
  }
  return (await res.json()) as TaskRedisSnapshot;
}

export function isTerminalTaskStatus(status: string | undefined): boolean {
  const st = (status ?? '').toLowerCase();
  // Python 只有在达到最大重试轮次后才会把 engineer_failed 写成最终状态。
  return (
    st === 'completed' ||
    st === 'completed_with_warnings' ||
    st === 'engineer_failed' ||
    st === 'failed' ||
    st === 'submit_failed' ||
    st === 'inspector_rejected'
  );
}

export function isSuccessTerminalStatus(status: string | undefined): boolean {
  const st = (status ?? '').toLowerCase();
  return st === 'completed' || st === 'completed_with_warnings';
}
