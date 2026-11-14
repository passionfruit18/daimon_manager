import type {
  Channel,
  ChatRequest,
  Commit,
  CommitGraph,
  DaimonClient,
  Health,
  Instance,
  Interaction,
} from '../types';
import { MockDaimonClient } from '../data/mock';

export const DAEMON_URL: string = import.meta.env.VITE_DAIMON_URL ?? 'http://127.0.0.1:7777';

export class DaemonError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

/** Talks to the local Quarkus daemon over HTTP. */
export class HttpDaimonClient implements DaimonClient {
  readonly mode = 'live' as const;

  constructor(private readonly baseUrl: string = DAEMON_URL) {}

  private async request<T>(method: string, path: string, body?: unknown, timeoutMs = 8000): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}/api${path}`, {
        method,
        headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      if (!res.ok) {
        let message = `${res.status} ${res.statusText}`;
        try {
          const err = (await res.json()) as { message?: string };
          if (err.message) message = err.message;
        } catch {
          /* non-JSON error body */
        }
        throw new DaemonError(message, res.status);
      }
      return (await res.json()) as T;
    } catch (e) {
      if (e instanceof DaemonError) throw e;
      throw new DaemonError(e instanceof Error ? e.message : String(e));
    } finally {
      clearTimeout(timer);
    }
  }

  health = () => this.request<Health>('GET', '/health', undefined, 1500);
  instances = () => this.request<Instance[]>('GET', '/instances');
  graph = () => this.request<CommitGraph>('GET', '/commits');
  channels = () => this.request<Channel[]>('GET', '/channels');
  interactions = () => this.request<Interaction[]>('GET', '/interactions');
  appendMemory = (id: string, entry: string) =>
    this.request<Instance>('POST', `/instances/${encodeURIComponent(id)}/memory`, { entry });
  commit = (id: string, message: string) =>
    this.request<Commit>('POST', `/instances/${encodeURIComponent(id)}/commits`, { message, author: 'operator' });
  checkout = (ref: string) => this.request<Instance>('POST', `/commits/${encodeURIComponent(ref)}/checkout`);
  createChannel = (name: string, description: string) =>
    this.request<Channel>('POST', '/channels', { name, description });
  setTeam = (channel: string, instanceIds: string[]) =>
    this.request<Channel>('PUT', `/channels/${encodeURIComponent(channel.replace(/^#/, ''))}/team`, {
      instanceIds,
    });
  startChat = (request: ChatRequest) => this.request<Interaction>('POST', '/interactions/chat', request);
  humanInput = (id: string, author: string, text: string) =>
    this.request<Interaction>('POST', `/interactions/${encodeURIComponent(id)}/input`, { author, text });
  cancel = (id: string) => this.request<Interaction>('POST', `/interactions/${encodeURIComponent(id)}/cancel`);
}

/** Uses the live daemon when it answers /api/health, otherwise the in-browser simulation. */
export async function detectClient(): Promise<DaimonClient> {
  if (import.meta.env.VITE_DAIMON_MOCK === 'true') return new MockDaimonClient();
  const live = new HttpDaimonClient();
  try {
    await live.health();
    return live;
  } catch {
    return new MockDaimonClient();
  }
}
