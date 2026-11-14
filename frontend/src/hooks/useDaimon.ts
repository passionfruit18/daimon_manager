import { useCallback, useEffect, useRef, useState } from 'react';
import type { Channel, CommitGraph, DaimonClient, Health, Instance, Interaction } from '../types';
import { detectClient } from '../lib/api';

export interface DaimonState {
  client: DaimonClient | null;
  health: Health | null;
  instances: Instance[];
  graph: CommitGraph;
  channels: Channel[];
  interactions: Interaction[];
  error: string | null;
  lastSync: Date | null;
}

const EMPTY: DaimonState = {
  client: null,
  health: null,
  instances: [],
  graph: { commits: [], branches: {} },
  channels: [],
  interactions: [],
  error: null,
  lastSync: null,
};

/**
 * Connects to the daemon (or the mock), polls ecosystem state, and exposes `run` for mutations
 * that should trigger an immediate refresh. Polls faster while an interaction is running.
 */
export function useDaimon() {
  const [state, setState] = useState<DaimonState>(EMPTY);
  const clientRef = useRef<DaimonClient | null>(null);

  const refresh = useCallback(async () => {
    const client = clientRef.current;
    if (!client) return;
    try {
      const [health, instances, graph, channels, interactions] = await Promise.all([
        client.health(),
        client.instances(),
        client.graph(),
        client.channels(),
        client.interactions(),
      ]);
      setState((s) => ({ ...s, health, instances, graph, channels, interactions, error: null, lastSync: new Date() }));
    } catch (e) {
      setState((s) => ({ ...s, error: e instanceof Error ? e.message : String(e) }));
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    detectClient().then((client) => {
      if (cancelled) return;
      clientRef.current = client;
      setState((s) => ({ ...s, client }));
      void refresh();
    });
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  const anyRunning = state.interactions.some((i) => i.status === 'RUNNING' || i.status === 'PENDING');
  useEffect(() => {
    if (!state.client) return;
    const id = setInterval(() => void refresh(), anyRunning ? 600 : 3000);
    return () => clearInterval(id);
  }, [state.client, anyRunning, refresh]);

  /** Runs a client mutation, surfaces errors, then refreshes. */
  const run = useCallback(
    async <T,>(fn: (client: DaimonClient) => Promise<T>): Promise<T | undefined> => {
      const client = clientRef.current;
      if (!client) return undefined;
      try {
        const result = await fn(client);
        await refresh();
        return result;
      } catch (e) {
        setState((s) => ({ ...s, error: e instanceof Error ? e.message : String(e) }));
        return undefined;
      }
    },
    [refresh],
  );

  const dismissError = useCallback(() => setState((s) => ({ ...s, error: null })), []);

  return { ...state, refresh, run, dismissError };
}
