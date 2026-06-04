import { useCallback, useEffect, useRef } from 'react';
import { Client, type IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
  createAnalysisTask,
  getTaskSnapshot,
  getWsBaseUrl,
  isTerminalTaskStatus,
  type TaskCreatePayload,
  type TaskRedisSnapshot,
} from '../api/tasks';
import { resolveAnalysisDatesFromMessage, resolveRegionFromMessage } from '../lib/regionFromMessage';

export const MULTI_AGENT_PENDING_KEY = '__multi_agent_pending__';

export type AgentRole = 'Director' | 'Analyst' | 'Engineer' | 'Inspector' | 'api' | 'System';

const ROLE_ALIASES: Record<string, AgentRole> = {
  director: 'Director',
  analyst: 'Analyst',
  engineer: 'Engineer',
  inspector: 'Inspector',
  api: 'api',
  system: 'System',
};

function normalizeRole(node: string | undefined): AgentRole {
  if (!node) return 'System';
  const key = node.trim().toLowerCase();
  return ROLE_ALIASES[key] ?? 'System';
}

function formatThinkingText(snap: TaskRedisSnapshot, trailLines: string[]): string {
  const header = '智能体正在协作分析…';
  const body = trailLines.length ? trailLines.slice(-6).join('\n') : snap.last_message || '正在连接…';
  const status = snap.status ? `\n\n状态：${snap.status}` : '';
  return `${header}\n\n${body}${status}`;
}

function collectTrailLines(snap: TaskRedisSnapshot, fromIndex: number): { lines: string[]; nextIndex: number } {
  const trail = snap.progress;
  if (!Array.isArray(trail)) {
    const node = snap.current_node;
    const msg = snap.last_message;
    if (node && msg) {
      return { lines: [`${normalizeRole(node)}：${msg}`], nextIndex: fromIndex };
    }
    return { lines: [], nextIndex: fromIndex };
  }
  if (trail.length <= fromIndex) {
    return { lines: [], nextIndex: fromIndex };
  }
  const fresh = trail.slice(fromIndex);
  const lines = fresh.map((e) => `${normalizeRole(e.node)}：${e.message ?? ''}`);
  return { lines, nextIndex: trail.length };
}

const POLL_INTERVAL_MS = 2000;
/** GEE 导出 + 多轮 LLM 可能超过 10 分钟 */
const POLL_IDLE_GIVE_UP_MS = 20 * 60 * 1000;
const POLL_ABSOLUTE_MAX_MS = 30 * 60 * 1000;

export function useMultiAgentTaskRunner(apiBase: string) {
  const clientRef = useRef<Client | null>(null);
  const progressLenRef = useRef(0);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollStartedAtRef = useRef(0);
  const lastProgressAtRef = useRef(0);

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  const disconnect = useCallback(() => {
    stopPolling();
    const c = clientRef.current;
    if (c) {
      try {
        c.deactivate();
      } catch {}
      clientRef.current = null;
    }
  }, [stopPolling]);

  useEffect(() => () => disconnect(), [disconnect]);

  const runAnalysis = useCallback(
    async (
      userMessage: string,
      handlers: {
        onThinking: (text: string) => void;
        onSnapshot: (snap: TaskRedisSnapshot) => void;
        onDone: (snap: TaskRedisSnapshot) => void;
        onError: (message: string) => void;
      },
    ) => {
      disconnect();
      progressLenRef.current = 0;

      const dates = resolveAnalysisDatesFromMessage(userMessage);
      const region = resolveRegionFromMessage(userMessage);
      const payload: TaskCreatePayload = {
        message: userMessage,
        regionCoords: region,
        startDate: dates.startDate,
        endDate: dates.endDate,
        compareStartDate: dates.compareStartDate,
        compareEndDate: dates.compareEndDate,
      };

      let taskId: string;
      try {
        const created = await createAnalysisTask(apiBase, payload);
        taskId = created.id;
      } catch (e) {
        handlers.onError(e instanceof Error ? e.message : String(e));
        return;
      }

      const trailAccumulator: string[] = [];

      const handleSnap = (snap: TaskRedisSnapshot) => {
        handlers.onSnapshot(snap);
        const prevLen = progressLenRef.current;
        const { lines, nextIndex } = collectTrailLines(snap, progressLenRef.current);
        progressLenRef.current = nextIndex;
        if (nextIndex > prevLen) {
          lastProgressAtRef.current = Date.now();
        }
        for (const line of lines) {
          if (line.trim()) trailAccumulator.push(line);
        }
        handlers.onThinking(formatThinkingText(snap, trailAccumulator));

        if (isTerminalTaskStatus(snap.status)) {
          disconnect();
          handlers.onDone(snap);
        }
      };

      const pollOnce = async () => {
        try {
          const snap = await getTaskSnapshot(apiBase, taskId);
          handleSnap(snap);
        } catch (e) {
          // 仅任务不存在时忽略；其它错误在超时后由 startPolling 提示
          const msg = e instanceof Error ? e.message : String(e);
          if (!msg.includes('404') && !msg.includes('任务不存在')) {
            console.warn('[multi-agent] snapshot poll', msg);
          }
        }
      };

      const startPolling = () => {
        stopPolling();
        const now = Date.now();
        pollStartedAtRef.current = now;
        lastProgressAtRef.current = now;
        void pollOnce();
        pollTimerRef.current = setInterval(() => {
          const t = Date.now();
          if (t - pollStartedAtRef.current > POLL_ABSOLUTE_MAX_MS) {
            stopPolling();
            handlers.onError('分析超过 30 分钟仍未结束，请查看 agent-api 日志或 snapshot 中的 message。');
            disconnect();
            return;
          }
          if (t - lastProgressAtRef.current > POLL_IDLE_GIVE_UP_MS) {
            stopPolling();
            handlers.onError(
              '超过 20 分钟没有新的协作进度（可能卡在 GEE 导出）。请查看 agent-api / mcp-gee 日志与 snapshot 的 message 字段。',
            );
            disconnect();
            return;
          }
          void pollOnce();
        }, POLL_INTERVAL_MS);
      };

      const wsBase = getWsBaseUrl();
      const client = new Client({
        webSocketFactory: () => new SockJS(`${wsBase}/ws`),
        reconnectDelay: 4000,
        onConnect: () => {
          client.subscribe(`/topic/task/${taskId}`, (message: IMessage) => {
            try {
              handleSnap(JSON.parse(message.body) as TaskRedisSnapshot);
            } catch {}
          });
          void pollOnce();
        },
        onStompError: (frame) => {
          handlers.onError(frame.headers['message'] || frame.body || 'WebSocket 连接失败');
        },
      });

      clientRef.current = client;
      client.activate();
      startPolling();
      handlers.onThinking(
        '智能体正在协作分析…\n\n任务已提交，等待 Director 响应…\n（若长时间无更新，请检查 Redis / WebSocket；界面每 2 秒轮询进度）',
      );
    },
    [apiBase, disconnect, stopPolling],
  );

  return { runAnalysis, disconnect };
}
