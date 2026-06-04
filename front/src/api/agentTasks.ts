export type AgentTaskState = {
  task_id?: string;
  status?: string;
  user_message?: string;
  answer?: string;
  intent?: string;
  tool_results?: unknown[];
  error?: string;
  context?: Record<string, unknown>;
};

const POLL_MS = 1500;
const MAX_WAIT_MS = 600_000;

export async function submitAgentTask(
  baseUrl: string,
  message: string,
  context?: Record<string, unknown>,
): Promise<AgentTaskState> {
  const res = await fetch(`${baseUrl.replace(/\/$/, '')}/api/agent/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, context: context ?? {} }),
  });
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(`提交智能体任务失败 ${res.status}: ${raw.slice(0, 500)}`);
  }
  return (await res.json()) as AgentTaskState;
}

export async function getAgentTask(baseUrl: string, taskId: string): Promise<AgentTaskState> {
  const res = await fetch(`${baseUrl.replace(/\/$/, '')}/api/agent/tasks/${encodeURIComponent(taskId)}`);
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(`查询任务失败 ${res.status}: ${raw.slice(0, 500)}`);
  }
  return (await res.json()) as AgentTaskState;
}

export async function pollAgentTaskUntilDone(
  baseUrl: string,
  taskId: string,
  onProgress?: (state: AgentTaskState) => void,
): Promise<AgentTaskState> {
  const start = Date.now();
  for (;;) {
    const state = await getAgentTask(baseUrl, taskId);
    onProgress?.(state);
    const st = (state.status ?? '').toLowerCase();
    if (st === 'completed' || st === 'failed') {
      return state;
    }
    if (Date.now() - start > MAX_WAIT_MS) {
      throw new Error('智能体任务超时，请稍后重试');
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
}

export function useAgentV2(): boolean {
  const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  return env?.VITE_USE_AGENT_V2 === 'true';
}
