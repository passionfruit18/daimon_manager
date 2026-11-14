import { FileText, GitBranch, GitFork, GitMerge, ShieldAlert, ShieldCheck, User } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { CommitGraph, Instance } from '../types';
import { layoutGraph, lineageOf, type GraphEdge } from '../lib/lineage';
import { derivePhenotype } from '../lib/phenotype';
import { short } from '../lib/hash';
import { MonsterAvatar } from './MonsterAvatar';
import { CopyCommand } from './CopyCommand';

const LANE_COLORS = ['#06b6d4', '#ec4899', '#10b981', '#eab308', '#8b5cf6', '#f97316'];
const ROW_H = 46;
const LANE_W = 22;
const PAD = 16;

const laneColor = (lane: number) => LANE_COLORS[lane % LANE_COLORS.length]!;
const x = (lane: number) => PAD + lane * LANE_W;
const y = (row: number) => ROW_H / 2 + row * ROW_H;

function edgePath(e: GraphEdge): string {
  const x1 = x(e.from.lane);
  const y1 = y(e.from.row);
  const x2 = x(e.to.lane);
  const y2 = y(e.to.row);
  const xl = x(e.lane);
  if (e.to.row - e.from.row === 1) {
    return `M${x1},${y1} C${x1},${y1 + ROW_H * 0.6} ${x2},${y2 - ROW_H * 0.6} ${x2},${y2}`;
  }
  // Leave the child into the carrying lane, run straight down, then curve into the parent.
  const yA = y1 + ROW_H;
  const yB = y2 - ROW_H;
  return [
    `M${x1},${y1}`,
    `C${x1},${y1 + ROW_H * 0.6} ${xl},${yA - ROW_H * 0.6} ${xl},${yA}`,
    `L${xl},${yB}`,
    `C${xl},${yB + ROW_H * 0.6} ${x2},${y2 - ROW_H * 0.6} ${x2},${y2}`,
  ].join(' ');
}

interface Props {
  graph: CommitGraph;
  instances: Instance[];
  onCheckout: (hash: string) => void;
}

/** Branching commit tree of every DAIMon lineage, with forks (branches) and breeding (merges). */
export function GitGraph({ graph, instances, onCheckout }: Props) {
  const { rows, edges, laneCount } = useMemo(() => layoutGraph(graph.commits), [graph.commits]);
  const [selected, setSelected] = useState<string | null>(null);
  const selectedRow = rows.find((r) => r.commit.hash === selected) ?? rows[0];
  const headsByHash = useMemo(() => {
    const m = new Map<string, Instance[]>();
    for (const i of instances) if (i.headHash) m.set(i.headHash, [...(m.get(i.headHash) ?? []), i]);
    return m;
  }, [instances]);

  const graphWidth = PAD * 2 + (laneCount - 1) * LANE_W;
  const height = Math.max(ROW_H, rows.length * ROW_H);

  if (rows.length === 0) {
    return <div className="glass p-8 text-center text-sm text-slate-500">No serialization commits yet.</div>;
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
      <section className="glass overflow-hidden">
        <header className="flex items-center justify-between border-b border-cyan-500/20 px-4 py-2.5">
          <h2 className="flex items-center gap-2 text-sm font-bold uppercase tracking-[0.2em] text-cyan-300">
            <GitBranch className="h-4 w-4" /> Lineage graph
          </h2>
          <span className="text-[11px] text-slate-500">
            {rows.length} commits · {Object.keys(graph.branches).length} branches
          </span>
        </header>
        <div className="max-h-[calc(100vh-220px)] overflow-auto">
          <div className="relative" style={{ height }}>
            <svg className="absolute left-0 top-0" width={graphWidth} height={height}>
              <defs>
                <filter id="node-glow" x="-100%" y="-100%" width="300%" height="300%">
                  <feGaussianBlur stdDeviation="2.5" result="b" />
                  <feMerge>
                    <feMergeNode in="b" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>
              {edges.map((e, i) => (
                <path
                  key={i}
                  d={edgePath(e)}
                  stroke={laneColor(e.lane)}
                  strokeWidth={2}
                  strokeOpacity={0.75}
                  fill="none"
                />
              ))}
              {rows.map((r) => {
                const isMerge = r.commit.parents.length > 1;
                const isSel = r.commit.hash === selectedRow?.commit.hash;
                return (
                  <g key={r.commit.hash} filter="url(#node-glow)">
                    {isSel && <circle cx={x(r.lane)} cy={y(r.row)} r={10} fill="none" stroke="#f8fafc" strokeOpacity={0.6} />}
                    {isMerge ? (
                      <rect
                        x={x(r.lane) - 6}
                        y={y(r.row) - 6}
                        width={12}
                        height={12}
                        transform={`rotate(45 ${x(r.lane)} ${y(r.row)})`}
                        fill="#0b0f19"
                        stroke="#eab308"
                        strokeWidth={2.5}
                      />
                    ) : (
                      <circle
                        cx={x(r.lane)}
                        cy={y(r.row)}
                        r={6}
                        fill={r.commit.parents.length === 0 ? laneColor(r.lane) : '#0b0f19'}
                        stroke={laneColor(r.lane)}
                        strokeWidth={2.5}
                      />
                    )}
                  </g>
                );
              })}
            </svg>

            {rows.map((r) => {
              const isSel = r.commit.hash === selectedRow?.commit.hash;
              const heads = headsByHash.get(r.commit.hash) ?? [];
              return (
                <button
                  type="button"
                  key={r.commit.hash}
                  onClick={() => setSelected(r.commit.hash)}
                  className={`absolute right-0 flex items-center gap-3 border-l-2 pr-4 text-left transition-colors ${
                    isSel ? 'border-cyan-400 bg-cyan-500/10' : 'border-transparent hover:bg-slate-800/40'
                  }`}
                  style={{ top: r.row * ROW_H, height: ROW_H, left: graphWidth + 4, paddingLeft: 10 }}
                >
                  <span className="w-[60px] shrink-0 font-mono text-xs text-yellow-300/90">{r.commit.shortHash}</span>
                  <span className="flex min-w-0 flex-1 items-center gap-2">
                    {r.commit.branches.map((b) => (
                      <span
                        key={b}
                        className="shrink-0 rounded border px-1.5 py-px text-[10px] font-semibold"
                        style={{ color: laneColor(r.lane), borderColor: `${laneColor(r.lane)}80`, background: `${laneColor(r.lane)}14` }}
                      >
                        {b}
                      </span>
                    ))}
                    {heads.some((h) => h.changed) && (
                      <span className="shrink-0 rounded border border-yellow-500/60 px-1.5 py-px text-[10px] text-yellow-300">
                        HEAD*
                      </span>
                    )}
                    <span className="truncate text-sm text-slate-200">{r.commit.message}</span>
                  </span>
                  <span className="hidden shrink-0 text-[11px] text-slate-500 md:inline">{r.commit.instanceName}</span>
                  <span className="hidden w-24 shrink-0 text-right text-[11px] text-slate-600 xl:inline">
                    {relativeTime(r.commit.createdAt)}
                  </span>
                  {r.commit.verified ? (
                    <ShieldCheck className="h-3.5 w-3.5 shrink-0 text-emerald-400/80" />
                  ) : (
                    <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-pink-400/80" />
                  )}
                </button>
              );
            })}
          </div>
        </div>
      </section>

      {selectedRow && <CommitDetail key={selectedRow.commit.hash} graph={graph} hash={selectedRow.commit.hash} onCheckout={onCheckout} />}
    </div>
  );
}

function CommitDetail({ graph, hash, onCheckout }: { graph: CommitGraph; hash: string; onCheckout: (hash: string) => void }) {
  const commit = graph.commits.find((c) => c.hash === hash)!;
  const lineage = lineageOf(graph, hash)!;
  const phenotype = derivePhenotype(lineage.rootHash, hash, lineage.generation, commit.files.length);
  return (
    <aside className="glass flex flex-col gap-4 p-4">
      <div className="flex items-center gap-3">
        <div className="rounded-xl border border-slate-700/70 bg-obsidian-900/80">
          <MonsterAvatar phenotype={phenotype} size={84} />
        </div>
        <div className="min-w-0">
          <p className="label">Snapshot of</p>
          <h3 className="truncate font-display text-xl font-bold text-slate-50">{commit.instanceName}</h3>
          <p className="text-xs" style={{ color: phenotype.element.color }}>
            {phenotype.element.name} · Gen {lineage.generation}
          </p>
        </div>
      </div>

      <div>
        <p className="label mb-1">Message</p>
        <p className="text-sm text-slate-200">{commit.message}</p>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 text-xs">
        <dt className="text-slate-500">commit</dt>
        <dd className="break-all font-mono text-yellow-300/90">{commit.hash}</dd>
        <dt className="text-slate-500">tree</dt>
        <dd className="break-all font-mono text-slate-400">{commit.treeHash}</dd>
        <dt className="text-slate-500">parents</dt>
        <dd className="flex flex-wrap gap-1 font-mono text-cyan-300">
          {commit.parents.length === 0 ? <span className="text-slate-600">(root)</span> : commit.parents.map((p) => <span key={p}>{short(p)}</span>)}
          {commit.parents.length > 1 && <GitMerge className="h-3.5 w-3.5 text-yellow-400" />}
        </dd>
        <dt className="text-slate-500">author</dt>
        <dd className="flex items-center gap-1 text-slate-300">
          <User className="h-3 w-3" /> {commit.author}
        </dd>
        <dt className="text-slate-500">date</dt>
        <dd className="text-slate-300">{new Date(commit.createdAt).toLocaleString()}</dd>
        <dt className="text-slate-500">signature</dt>
        <dd className={commit.verified ? 'text-emerald-400' : 'text-pink-400'}>
          {commit.verified ? 'Ed25519 · verified' : commit.signed ? 'INVALID' : 'unsigned'}
        </dd>
      </dl>

      <div>
        <p className="label mb-1">Files</p>
        <ul className="space-y-0.5">
          {commit.files.map((f) => (
            <li key={f} className="flex items-center gap-1.5 text-xs text-slate-300">
              <FileText className="h-3 w-3 text-slate-500" /> {f}
            </li>
          ))}
        </ul>
      </div>

      <div className="mt-auto space-y-2">
        <CopyCommand command={`git checkout ${commit.shortHash}`} />
        <button type="button" className="btn-magenta w-full justify-center" onClick={() => onCheckout(commit.hash)}>
          <GitFork className="h-3.5 w-3.5" /> Spawn instance from this commit
        </button>
      </div>
    </aside>
  );
}

function relativeTime(iso: string): string {
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}
