import { Activity, Hash, MessageSquare, Play, Plus, Radio, Send, Square, Terminal, User, Users } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Channel, ChatRequest, CommitGraph, Instance, Interaction, InteractionStatus } from '../types';
import { derivePhenotype, type Phenotype } from '../lib/phenotype';
import { lineageOf } from '../lib/lineage';
import { MonsterAvatar } from './MonsterAvatar';

interface Props {
  instances: Instance[];
  graph: CommitGraph;
  channels: Channel[];
  interactions: Interaction[];
  onCreateChannel: (name: string, description: string) => Promise<unknown>;
  onSetTeam: (channel: string, ids: string[]) => Promise<unknown>;
  onStartChat: (request: ChatRequest) => Promise<Interaction | undefined>;
  onHumanInput: (id: string, text: string) => Promise<unknown>;
  onCancel: (id: string) => Promise<unknown>;
}

const STATUS_STYLE: Record<InteractionStatus, string> = {
  PENDING: 'border-slate-500/50 text-slate-300',
  RUNNING: 'border-emerald-500/60 bg-emerald-500/10 text-emerald-300',
  COMPLETED: 'border-cyan-500/50 text-cyan-300',
  CANCELLED: 'border-yellow-500/50 text-yellow-300',
  FAILED: 'border-pink-500/60 bg-pink-500/10 text-pink-300',
};

/** Channels, team exposure, interaction requests, and live chat logs with human-in-the-loop input. */
export function InteractionPanel(props: Props) {
  const { instances, graph, channels, interactions } = props;
  const [channelName, setChannelName] = useState<string | null>(null);
  const [selectedChat, setSelectedChat] = useState<string | null>(null);

  const channel = channels.find((c) => c.name === channelName) ?? channels[0] ?? null;
  const chat = interactions.find((i) => i.id === selectedChat) ?? interactions[0] ?? null;

  const phenotypes = useMemo(() => {
    const m = new Map<string, Phenotype>();
    for (const i of instances) {
      const head = i.headHash ?? i.id;
      const lineage = lineageOf(graph, i.headHash);
      m.set(i.id, derivePhenotype(lineage?.rootHash ?? head, head, lineage?.generation ?? 0, i.files.length));
    }
    return m;
  }, [instances, graph]);

  return (
    <div className="grid gap-4 xl:grid-cols-[300px_minmax(0,1fr)_minmax(0,1.35fr)]">
      <ChannelColumn {...props} channel={channel} onSelect={setChannelName} phenotypes={phenotypes} />
      <div className="flex min-w-0 flex-col gap-4">
        <RequestForm
          instances={instances}
          channel={channel}
          onStart={async (req) => {
            const created = await props.onStartChat(req);
            if (created) setSelectedChat(created.id);
          }}
        />
        <section className="glass flex min-h-0 flex-col">
          <header className="flex items-center justify-between border-b border-cyan-500/20 px-4 py-2.5">
            <h2 className="flex items-center gap-2 text-sm font-bold uppercase tracking-[0.2em] text-cyan-300">
              <Activity className="h-4 w-4" /> Interactions
            </h2>
            <span className="text-[11px] text-slate-500">
              {interactions.filter((i) => i.status === 'RUNNING').length} running
            </span>
          </header>
          <ul className="max-h-72 divide-y divide-slate-800/80 overflow-auto">
            {interactions.length === 0 && <li className="p-4 text-xs text-slate-600">No interactions yet. Request one above.</li>}
            {interactions.map((i) => (
              <li key={i.id}>
                <button
                  type="button"
                  onClick={() => setSelectedChat(i.id)}
                  className={`flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors ${
                    i.id === chat?.id ? 'bg-cyan-500/10' : 'hover:bg-slate-800/40'
                  }`}
                >
                  <MessageSquare className="h-4 w-4 shrink-0 text-slate-500" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-slate-200">{i.topic}</p>
                    <p className="truncate text-[11px] text-slate-500">
                      {i.channel ?? 'direct'} · {i.participants.map((p) => p.name).join(' ↔ ')}
                    </p>
                  </div>
                  <span className="text-[11px] tabular-nums text-slate-500">
                    {i.completedTurns}/{i.turnLimit}
                  </span>
                  <span className={`rounded-full border px-2 py-0.5 text-[10px] font-bold tracking-wider ${STATUS_STYLE[i.status]}`}>
                    {i.status}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      </div>
      <ChatLog chat={chat} phenotypes={phenotypes} onHumanInput={props.onHumanInput} onCancel={props.onCancel} />
    </div>
  );
}

// ---------------------------------------------------------------------------------------------

function ChannelColumn({
  channels,
  channel,
  instances,
  phenotypes,
  onSelect,
  onCreateChannel,
  onSetTeam,
}: Props & { channel: Channel | null; phenotypes: Map<string, Phenotype>; onSelect: (name: string) => void }) {
  const [newName, setNewName] = useState('');

  const toggle = (id: string) => {
    if (!channel) return;
    const team = channel.teamIds.includes(id) ? channel.teamIds.filter((t) => t !== id) : [...channel.teamIds, id];
    void onSetTeam(channel.name, team);
  };

  return (
    <section className="glass flex flex-col">
      <header className="flex items-center gap-2 border-b border-cyan-500/20 px-4 py-2.5">
        <Radio className="h-4 w-4 text-pink-400" />
        <h2 className="text-sm font-bold uppercase tracking-[0.2em] text-pink-300">Local channels</h2>
      </header>

      <ul className="space-y-1 p-2">
        {channels.map((c) => (
          <li key={c.name}>
            <button
              type="button"
              onClick={() => onSelect(c.name)}
              className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left transition-colors ${
                c.name === channel?.name ? 'bg-pink-500/10 text-pink-200' : 'text-slate-300 hover:bg-slate-800/50'
              }`}
            >
              <Hash className="h-3.5 w-3.5 shrink-0 text-pink-400/80" />
              <span className="flex-1 truncate text-sm">{c.name.replace(/^#/, '')}</span>
              <span className="flex -space-x-2">
                {c.teamIds.slice(0, 4).map((id) => {
                  const p = phenotypes.get(id);
                  return p ? (
                    <span key={id} className="rounded-full border border-obsidian bg-obsidian-700">
                      <MonsterAvatar phenotype={p} size={22} />
                    </span>
                  ) : null;
                })}
              </span>
            </button>
          </li>
        ))}
      </ul>

      <form
        className="flex gap-1.5 px-3 pb-3"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!newName.trim()) return;
          await onCreateChannel(newName.trim().toLowerCase(), '');
          onSelect(newName.trim().startsWith('#') ? newName.trim().toLowerCase() : `#${newName.trim().toLowerCase()}`);
          setNewName('');
        }}
      >
        <input className="input text-xs" placeholder="new-channel" value={newName} onChange={(e) => setNewName(e.target.value)} />
        <button type="submit" className="btn-magenta" disabled={!newName.trim()} title="Create channel">
          <Plus className="h-3.5 w-3.5" />
        </button>
      </form>

      {channel && (
        <div className="border-t border-slate-800 p-3">
          <p className="label mb-1 flex items-center gap-1">
            <Users className="h-3 w-3" /> Team exposed on {channel.name}
          </p>
          {channel.description && <p className="mb-2 text-xs text-slate-500">{channel.description}</p>}
          <div className="space-y-1">
            {instances.map((i) => {
              const on = channel.teamIds.includes(i.id);
              const p = phenotypes.get(i.id);
              return (
                <button
                  type="button"
                  key={i.id}
                  onClick={() => toggle(i.id)}
                  className={`flex w-full items-center gap-2 rounded-md border px-2 py-1 text-left text-xs transition-all ${
                    on ? 'border-emerald-500/50 bg-emerald-500/10 text-emerald-200' : 'border-slate-800 text-slate-400 hover:border-slate-600'
                  }`}
                >
                  {p && <MonsterAvatar phenotype={p} size={24} />}
                  <span className="flex-1 truncate">{i.name}</span>
                  <span className="truncate text-[10px] text-slate-500">{i.branch}</span>
                  <span className={`h-2 w-2 rounded-full ${on ? 'bg-emerald-400 shadow-[0_0_8px_#10b981]' : 'bg-slate-700'}`} />
                </button>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------------------------

function RequestForm({
  instances,
  channel,
  onStart,
}: {
  instances: Instance[];
  channel: Channel | null;
  onStart: (req: ChatRequest) => Promise<void>;
}) {
  const [topic, setTopic] = useState('Should every commit be signed?');
  const [turns, setTurns] = useState(6);
  const [useChannel, setUseChannel] = useState(true);
  const [picked, setPicked] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const participantIds = useChannel ? (channel?.teamIds ?? []) : picked;

  return (
    <section className="glass p-4">
      <h2 className="mb-3 flex items-center gap-2 text-sm font-bold uppercase tracking-[0.2em] text-yellow-300">
        <Play className="h-4 w-4" /> Request interaction
      </h2>
      <form
        className="space-y-3"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          try {
            await onStart({
              topic,
              turnLimit: turns,
              participantIds,
              channel: useChannel ? (channel?.name ?? null) : null,
              turnDelayMs: 1200,
            });
          } finally {
            setBusy(false);
          }
        }}
      >
        <div>
          <label className="label mb-1 block" htmlFor="topic">
            Topic
          </label>
          <input id="topic" className="input" value={topic} onChange={(e) => setTopic(e.target.value)} />
        </div>

        <div className="flex items-center gap-3">
          <label className="label shrink-0" htmlFor="turns">
            Turn limit
          </label>
          <input
            id="turns"
            type="range"
            min={1}
            max={24}
            value={turns}
            onChange={(e) => setTurns(Number(e.target.value))}
            className="flex-1 accent-yellow-400"
          />
          <span className="w-6 text-right text-sm font-bold tabular-nums text-yellow-300">{turns}</span>
        </div>

        <div className="flex gap-1 rounded-md border border-slate-800 p-0.5 text-[11px]">
          {[
            { v: true, label: `Channel team${channel ? ` (${channel.name})` : ''}` },
            { v: false, label: 'Pick DAIMons' },
          ].map((o) => (
            <button
              key={String(o.v)}
              type="button"
              onClick={() => setUseChannel(o.v)}
              className={`flex-1 rounded px-2 py-1 font-semibold uppercase tracking-wider transition-colors ${
                useChannel === o.v ? 'bg-yellow-500/15 text-yellow-200' : 'text-slate-500 hover:text-slate-300'
              }`}
            >
              {o.label}
            </button>
          ))}
        </div>

        {!useChannel && (
          <div className="flex flex-wrap gap-1.5">
            {instances.map((i) => {
              const idx = picked.indexOf(i.id);
              return (
                <button
                  type="button"
                  key={i.id}
                  onClick={() => setPicked(idx >= 0 ? picked.filter((p) => p !== i.id) : [...picked, i.id])}
                  className={`rounded-full border px-2.5 py-0.5 text-xs transition-colors ${
                    idx >= 0 ? 'border-yellow-500/60 bg-yellow-500/10 text-yellow-200' : 'border-slate-700 text-slate-400 hover:border-slate-500'
                  }`}
                >
                  {idx >= 0 && <span className="mr-1 font-bold">{idx + 1}</span>}
                  {i.name}
                </button>
              );
            })}
          </div>
        )}

        <button type="submit" className="btn-yellow w-full justify-center" disabled={busy || participantIds.length === 0}>
          <Play className="h-3.5 w-3.5" /> Start chat · {participantIds.length} DAIMon{participantIds.length === 1 ? '' : 's'}
        </button>
      </form>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------

function ChatLog({
  chat,
  phenotypes,
  onHumanInput,
  onCancel,
}: {
  chat: Interaction | null;
  phenotypes: Map<string, Phenotype>;
  onHumanInput: (id: string, text: string) => Promise<unknown>;
  onCancel: (id: string) => Promise<unknown>;
}) {
  const [draft, setDraft] = useState('');
  const scroller = useRef<HTMLDivElement>(null);
  const count = chat?.log.length ?? 0;

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
  }, [count, chat?.id]);

  if (!chat) {
    return (
      <section className="glass flex min-h-[420px] items-center justify-center p-6 text-sm text-slate-600">
        <Terminal className="mr-2 h-4 w-4" /> Select or start an interaction to watch its log.
      </section>
    );
  }
  const running = chat.status === 'RUNNING' || chat.status === 'PENDING';

  return (
    <section className="glass flex h-[calc(100vh-200px)] min-h-[420px] flex-col">
      <header className="flex items-center gap-3 border-b border-cyan-500/20 px-4 py-2.5">
        <Terminal className="h-4 w-4 shrink-0 text-emerald-400" />
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-bold text-slate-100">{chat.topic}</h2>
          <p className="text-[11px] text-slate-500">
            interactionLog · turn {chat.completedTurns}/{chat.turnLimit}
          </p>
        </div>
        <span className={`rounded-full border px-2 py-0.5 text-[10px] font-bold tracking-wider ${STATUS_STYLE[chat.status]}`}>
          {running && <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400" />}
          {chat.status}
        </span>
        {running && (
          <button type="button" className="btn-ghost" onClick={() => void onCancel(chat.id)} title="Cancel at next turn boundary">
            <Square className="h-3 w-3" />
          </button>
        )}
      </header>

      <div className="h-1 bg-slate-800">
        <div
          className="h-full bg-gradient-to-r from-cyan-500 via-pink-500 to-yellow-400 transition-all duration-500"
          style={{ width: `${(chat.completedTurns / chat.turnLimit) * 100}%` }}
        />
      </div>

      <div ref={scroller} className="flex-1 space-y-3 overflow-y-auto p-4">
        {chat.log.map((e) => {
          if (e.kind === 'SYSTEM') {
            return (
              <p key={e.sequence} className="text-center text-[11px] text-slate-600">
                ── {e.text} ──
              </p>
            );
          }
          if (e.kind === 'HUMAN') {
            return (
              <div key={e.sequence} className="flex justify-end">
                <div className="max-w-[80%] rounded-xl rounded-tr-sm border border-yellow-500/40 bg-yellow-500/10 px-3 py-2">
                  <p className="mb-0.5 flex items-center justify-end gap-1 text-[10px] font-bold uppercase tracking-widest text-yellow-300">
                    {e.speaker} <User className="h-3 w-3" />
                  </p>
                  <p className="text-sm text-slate-100">{e.text}</p>
                </div>
              </div>
            );
          }
          const p = e.participantId ? phenotypes.get(e.participantId) : undefined;
          const color = p?.element.color ?? '#06b6d4';
          return (
            <div key={e.sequence} className="flex gap-2">
              <div className="shrink-0">{p ? <MonsterAvatar phenotype={p} size={36} /> : <div className="h-9 w-9 rounded-full bg-slate-800" />}</div>
              <div className="max-w-[85%] rounded-xl rounded-tl-sm border bg-slate-900/70 px-3 py-2" style={{ borderColor: `${color}50` }}>
                <p className="mb-0.5 flex items-baseline gap-2 text-[10px] font-bold uppercase tracking-widest" style={{ color }}>
                  {e.speaker}
                  <span className="font-normal normal-case tracking-normal text-slate-600">
                    {e.roleName} · turn {e.turn + 1}
                  </span>
                </p>
                <p className="text-sm leading-relaxed text-slate-200">{e.text}</p>
              </div>
            </div>
          );
        })}
        {running && (
          <p className="flex items-center gap-2 text-xs text-slate-500">
            <span className="flex gap-0.5">
              {[0, 1, 2].map((i) => (
                <span key={i} className="h-1.5 w-1.5 animate-pulse rounded-full bg-cyan-400" style={{ animationDelay: `${i * 150}ms` }} />
              ))}
            </span>
            next DAIMon is thinking…
          </p>
        )}
      </div>

      <form
        className="flex gap-2 border-t border-slate-800 p-3"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!draft.trim()) return;
          await onHumanInput(chat.id, draft.trim());
          setDraft('');
        }}
      >
        <input
          className="input"
          disabled={!running}
          placeholder={running ? 'Interject as operator (delivered before the next turn)…' : 'Interaction finished'}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <button type="submit" className="btn-cyan" disabled={!running || !draft.trim()}>
          <Send className="h-3.5 w-3.5" />
        </button>
      </form>
    </section>
  );
}
