import { Brain, CircleDot, GitCommitHorizontal, GitFork, Plus, ShieldCheck, Sparkles } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { CommitGraph, Instance } from '../types';
import { derivePhenotype } from '../lib/phenotype';
import { lineageOf } from '../lib/lineage';
import { short } from '../lib/hash';
import { MonsterAvatar } from './MonsterAvatar';
import { CopyCommand } from './CopyCommand';

interface Props {
  instance: Instance;
  graph: CommitGraph;
  onCommit: (message: string) => void;
  onTeach: (entry: string) => void;
  onFork: () => void;
}

const TRAITS = [
  { key: 'complexity', label: 'Complexity', bar: 'from-cyan-500 to-cyan-300', text: 'text-cyan-300' },
  { key: 'nature', label: 'Nature', bar: 'from-emerald-500 to-emerald-300', text: 'text-emerald-300' },
  { key: 'drive', label: 'Drive', bar: 'from-pink-500 to-pink-300', text: 'text-pink-300' },
] as const;

/** Collectible creature card for one live DAIMon instance. */
export function MonsterCard({ instance, graph, onCommit, onTeach, onFork }: Props) {
  const [mode, setMode] = useState<'idle' | 'teach' | 'commit'>('idle');
  const [draft, setDraft] = useState('');

  const head = instance.headHash ?? instance.id;
  const lineage = useMemo(() => lineageOf(graph, instance.headHash), [graph, instance.headHash]);
  const headCommit = useMemo(() => graph.commits.find((c) => c.hash === instance.headHash), [graph, instance.headHash]);
  const phenotype = useMemo(
    () => derivePhenotype(lineage?.rootHash ?? head, head, lineage?.generation ?? 0, instance.files.length),
    [lineage, head, instance.files.length],
  );
  const facts = useMemo(
    () =>
      instance.memory
        .split('\n')
        .filter((l) => l.trim().startsWith('- '))
        .map((l) => l.trim().slice(2))
        .slice(-3)
        .reverse(),
    [instance.memory],
  );
  const isMerge = (headCommit?.parents.length ?? 0) > 1;

  const submit = () => {
    const text = draft.trim();
    if (!text) return;
    if (mode === 'teach') onTeach(text);
    if (mode === 'commit') onCommit(text);
    setDraft('');
    setMode('idle');
  };

  const { color, glow, name: elementName } = phenotype.element;

  return (
    <article
      className="group relative flex flex-col overflow-hidden rounded-2xl border bg-slate-900/60 p-[1px] backdrop-blur-md transition-transform duration-300 hover:-translate-y-1"
      style={{ borderColor: `${color}66`, boxShadow: `0 0 18px ${glow.replace('0.45', '0.18')}` }}
    >
      {/* Element-tinted frame */}
      <div
        className="pointer-events-none absolute inset-0 opacity-25"
        style={{ background: `linear-gradient(160deg, ${color}55, transparent 35%, transparent 70%, ${phenotype.secondary.color}44)` }}
      />
      <div className="holo pointer-events-none absolute inset-0" />

      <div className="relative flex flex-1 flex-col gap-3 rounded-2xl p-4">
        {/* Header */}
        <header className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h3 className="truncate font-display text-lg font-bold tracking-wide text-slate-50">{instance.name}</h3>
              {isMerge && (
                <span title="Bred from two lineages (merge commit)">
                  <Sparkles className="h-4 w-4 text-yellow-400" />
                </span>
              )}
            </div>
            <p className="truncate text-[10px] uppercase tracking-[0.2em] text-slate-500">
              {phenotype.natureLabel} · {phenotype.driveLabel}
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end">
            <span className="text-[9px] uppercase tracking-[0.25em] text-slate-500">Gen</span>
            <span className="font-display text-2xl font-bold leading-none" style={{ color }}>
              {phenotype.generation}
            </span>
          </div>
        </header>

        {/* Avatar window */}
        <div
          className="relative flex items-center justify-center overflow-hidden rounded-xl border bg-obsidian-900/80"
          style={{ borderColor: `${color}40` }}
        >
          <div className="scanlines pointer-events-none absolute inset-0" />
          <div
            className="pointer-events-none absolute inset-x-0 top-0 h-1/3 animate-scan opacity-40"
            style={{ background: `linear-gradient(to bottom, transparent, ${color}22, transparent)` }}
          />
          <MonsterAvatar phenotype={phenotype} size={150} dirty={instance.changed} />
          <span
            className="absolute left-2 top-2 rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase tracking-widest"
            style={{ color, borderColor: `${color}80`, background: `${color}1a` }}
          >
            {elementName}
          </span>
          {instance.changed && (
            <span className="absolute right-2 top-2 flex items-center gap-1 rounded-full border border-yellow-500/60 bg-yellow-500/10 px-2 py-0.5 text-[10px] font-bold uppercase tracking-widest text-yellow-300">
              <CircleDot className="h-3 w-3 animate-pulse" /> dirty
            </span>
          )}
        </div>

        {/* Trait bars */}
        <div className="space-y-1.5">
          {TRAITS.map((t) => {
            const value = phenotype.traits[t.key];
            return (
              <div key={t.key} className="flex items-center gap-2 text-[11px]">
                <span className="w-20 shrink-0 uppercase tracking-wider text-slate-400">{t.label}</span>
                <div className="relative h-2 flex-1 overflow-hidden rounded-full bg-slate-800">
                  <div
                    className={`h-full rounded-full bg-gradient-to-r ${t.bar} transition-all duration-700`}
                    style={{ width: `${value}%` }}
                  />
                </div>
                <span className={`w-7 text-right font-bold tabular-nums ${t.text}`}>{value}</span>
              </div>
            );
          })}
        </div>

        {/* Memory = "moves" */}
        <div className="space-y-1 rounded-lg border border-slate-800 bg-obsidian-900/60 p-2">
          <div className="label flex items-center gap-1">
            <Brain className="h-3 w-3" /> memory.md
          </div>
          {facts.length === 0 ? (
            <p className="text-xs italic text-slate-600">empty</p>
          ) : (
            facts.map((f, i) => (
              <p key={i} className="line-clamp-2 text-xs leading-snug text-slate-300">
                <span style={{ color }}>▸</span> {f}
              </p>
            ))
          )}
        </div>

        {/* Git state */}
        <div className="mt-auto space-y-1.5">
          <div className="flex items-center justify-between text-[10px] text-slate-500">
            <span className="flex min-w-0 items-center gap-1 truncate">
              <GitFork className="h-3 w-3 shrink-0" />
              <span className="truncate text-cyan-400/80">{instance.branch ?? 'detached'}</span>
            </span>
            {headCommit?.verified && (
              <span className="flex items-center gap-1 text-emerald-400/80" title="Ed25519 signature verified">
                <ShieldCheck className="h-3 w-3" /> signed
              </span>
            )}
          </div>
          <CopyCommand command={`git checkout ${short(instance.headHash)}`} />
        </div>

        {/* Actions */}
        {mode === 'idle' ? (
          <div className="flex gap-1.5">
            <button type="button" className="btn-cyan flex-1 justify-center" onClick={() => setMode('teach')}>
              <Plus className="h-3.5 w-3.5" /> Teach
            </button>
            <button
              type="button"
              className="btn-green flex-1 justify-center"
              disabled={!instance.changed}
              title={instance.changed ? 'Serialize workspace into a signed commit' : 'Nothing to commit'}
              onClick={() => {
                setDraft('');
                setMode('commit');
              }}
            >
              <GitCommitHorizontal className="h-3.5 w-3.5" /> Commit
            </button>
            <button
              type="button"
              className="btn-magenta justify-center"
              disabled={!instance.headHash}
              title="Spawn a new instance from this commit"
              onClick={onFork}
            >
              <GitFork className="h-3.5 w-3.5" />
            </button>
          </div>
        ) : (
          <form
            className="flex gap-1.5"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <input
              autoFocus
              className="input text-xs"
              placeholder={mode === 'teach' ? 'New memory, e.g. "Distrusts Voltmoth"' : 'Commit message'}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setMode('idle')}
            />
            <button type="submit" className={mode === 'teach' ? 'btn-cyan' : 'btn-green'} disabled={!draft.trim()}>
              OK
            </button>
          </form>
        )}
      </div>
    </article>
  );
}
