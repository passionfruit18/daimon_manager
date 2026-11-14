import { Cpu, GitBranch, LayoutGrid, Radio, RefreshCw, Wifi, WifiOff, X } from 'lucide-react';
import { useState } from 'react';
import { useDaimon } from './hooks/useDaimon';
import { DAEMON_URL } from './lib/api';
import { MonsterCard } from './components/MonsterCard';
import { GitGraph } from './components/GitGraph';
import { InteractionPanel } from './components/InteractionPanel';

type View = 'bestiary' | 'lineage' | 'channels';

const VIEWS: { id: View; label: string; icon: typeof LayoutGrid }[] = [
  { id: 'bestiary', label: 'Bestiary', icon: LayoutGrid },
  { id: 'lineage', label: 'Lineage', icon: GitBranch },
  { id: 'channels', label: 'Channels', icon: Radio },
];

export default function App() {
  const daimon = useDaimon();
  const [view, setViewState] = useState<View>(() => {
    const fromHash = window.location.hash.slice(1);
    return VIEWS.some((v) => v.id === fromHash) ? (fromHash as View) : 'bestiary';
  });
  const setView = (v: View) => {
    setViewState(v);
    history.replaceState(null, '', `#${v}`);
  };
  const { client, health, instances, graph, channels, interactions } = daimon;
  const running = interactions.filter((i) => i.status === 'RUNNING').length;
  const dirty = instances.filter((i) => i.changed).length;

  return (
    <div className="flex h-full flex-col">
      {/* Top bar */}
      <header className="sticky top-0 z-20 border-b border-cyan-500/20 bg-obsidian/80 backdrop-blur-xl">
        <div className="flex items-center gap-6 px-6 py-3">
          <div className="flex items-baseline gap-2">
            <h1 className="animate-flicker font-display text-2xl font-black tracking-tight">
              <span className="neon-text-cyan text-cyan-300">DAI</span>
              <span className="neon-text-magenta text-pink-400">Mon</span>
            </h1>
            <span className="hidden text-[10px] uppercase tracking-[0.3em] text-slate-500 md:inline">
              distributed ai monsters
            </span>
          </div>

          <nav className="flex gap-1 rounded-lg border border-slate-800 bg-obsidian-900/60 p-1">
            {VIEWS.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                type="button"
                onClick={() => setView(id)}
                className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold uppercase tracking-wider transition-all ${
                  view === id
                    ? 'bg-cyan-500/15 text-cyan-200 shadow-[0_0_12px_rgba(6,182,212,0.25)]'
                    : 'text-slate-500 hover:text-slate-200'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                {label}
                {id === 'channels' && running > 0 && (
                  <span className="ml-0.5 rounded-full bg-emerald-500/20 px-1.5 text-[10px] text-emerald-300">{running}</span>
                )}
              </button>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-4 text-[11px]">
            <Stat label="DAIMons" value={instances.length} />
            <Stat label="Commits" value={graph.commits.length} />
            <Stat label="Dirty" value={dirty} tone={dirty ? 'text-yellow-300' : undefined} />
            <div
              className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 ${
                client?.mode === 'live'
                  ? 'border-emerald-500/50 bg-emerald-500/10 text-emerald-300'
                  : 'border-yellow-500/50 bg-yellow-500/10 text-yellow-300'
              }`}
              title={
                client?.mode === 'live'
                  ? `Connected to ${DAEMON_URL} · signer ${health?.signerPublicKey.slice(-12) ?? ''}`
                  : `Daemon not reachable at ${DAEMON_URL}; running the in-browser simulation`
              }
            >
              {client?.mode === 'live' ? <Wifi className="h-3.5 w-3.5" /> : <WifiOff className="h-3.5 w-3.5" />}
              <span className="font-bold uppercase tracking-wider">{client ? (client.mode === 'live' ? 'Live' : 'Mock') : '…'}</span>
              {health && (
                <span className="flex items-center gap-1 text-slate-400">
                  <Cpu className="h-3 w-3" /> {health.geminiEnabled ? health.geminiModel : 'offline talk'}
                </span>
              )}
            </div>
            <button type="button" className="btn-ghost" onClick={() => void daimon.refresh()} title="Refresh">
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      </header>

      {daimon.error && (
        <div className="mx-6 mt-3 flex items-center gap-2 rounded-lg border border-pink-500/50 bg-pink-500/10 px-3 py-2 text-sm text-pink-200">
          <span className="font-bold">ERR</span> {daimon.error}
          <button type="button" className="ml-auto text-pink-300 hover:text-pink-100" onClick={daimon.dismissError} aria-label="Dismiss">
            <X className="h-4 w-4" />
          </button>
        </div>
      )}

      <main className="flex-1 overflow-auto px-6 py-5">
        {!client ? (
          <p className="mt-20 text-center text-sm text-slate-500">Connecting to daemon…</p>
        ) : view === 'bestiary' ? (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-5">
            {instances.map((i) => (
              <MonsterCard
                key={i.id}
                instance={i}
                graph={graph}
                onTeach={(entry) => void daimon.run((c) => c.appendMemory(i.id, entry))}
                onCommit={(message) => void daimon.run((c) => c.commit(i.id, message))}
                onFork={() => i.headHash && void daimon.run((c) => c.checkout(i.headHash!))}
              />
            ))}
          </div>
        ) : view === 'lineage' ? (
          <GitGraph graph={graph} instances={instances} onCheckout={(hash) => void daimon.run((c) => c.checkout(hash))} />
        ) : (
          <InteractionPanel
            instances={instances}
            graph={graph}
            channels={channels}
            interactions={interactions}
            onCreateChannel={(name, description) => daimon.run((c) => c.createChannel(name, description))}
            onSetTeam={(channel, ids) => daimon.run((c) => c.setTeam(channel, ids))}
            onStartChat={(req) => daimon.run((c) => c.startChat(req))}
            onHumanInput={(id, text) => daimon.run((c) => c.humanInput(id, 'operator', text))}
            onCancel={(id) => daimon.run((c) => c.cancel(id))}
          />
        )}
      </main>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="hidden flex-col items-end leading-tight lg:flex">
      <span className="text-[9px] uppercase tracking-[0.2em] text-slate-500">{label}</span>
      <span className={`font-bold tabular-nums ${tone ?? 'text-slate-200'}`}>{value}</span>
    </div>
  );
}
