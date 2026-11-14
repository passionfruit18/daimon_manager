import type { Commit, CommitGraph } from '../types';

export interface Lineage {
  rootHash: string;
  generation: number;
}

/** Follows first parents back to the lineage root. Generation = first-parent depth. */
export function lineageOf(graph: CommitGraph, headHash: string | null): Lineage | null {
  if (!headHash) return null;
  const byHash = new Map(graph.commits.map((c) => [c.hash, c]));
  let current = byHash.get(headHash);
  if (!current) return { rootHash: headHash, generation: 0 };
  let generation = 0;
  const seen = new Set<string>();
  while (current.parents.length > 0 && !seen.has(current.hash)) {
    seen.add(current.hash);
    const parent = byHash.get(current.parents[0]!);
    if (!parent) break;
    current = parent;
    generation++;
  }
  return { rootHash: current.hash, generation };
}

/** Children-before-parents ordering (newest first), stable by timestamp. */
export function topoSortNewestFirst(commits: Commit[]): Commit[] {
  const byHash = new Map(commits.map((c) => [c.hash, c]));
  const childCount = new Map<string, number>();
  for (const c of commits) {
    for (const p of c.parents) if (byHash.has(p)) childCount.set(p, (childCount.get(p) ?? 0) + 1);
  }
  const newer = (a: Commit, b: Commit) => b.createdAt.localeCompare(a.createdAt) || a.hash.localeCompare(b.hash);
  const ready = commits.filter((c) => !childCount.get(c.hash)).sort(newer);
  const out: Commit[] = [];
  while (ready.length) {
    const c = ready.shift()!;
    out.push(c);
    for (const p of c.parents) {
      const left = (childCount.get(p) ?? 0) - 1;
      childCount.set(p, left);
      if (left === 0) {
        const parent = byHash.get(p);
        if (parent) {
          ready.push(parent);
          ready.sort(newer);
        }
      }
    }
  }
  return out;
}

export interface GraphRow {
  commit: Commit;
  row: number;
  lane: number;
}

export interface GraphEdge {
  from: GraphRow;
  to: GraphRow;
  /** Lane the edge travels down while passing intermediate rows. */
  lane: number;
}

/** Classic Git lane assignment: each open lane "expects" a commit hash. */
export function layoutGraph(commits: Commit[]): { rows: GraphRow[]; edges: GraphEdge[]; laneCount: number } {
  const ordered = topoSortNewestFirst(commits);
  const lanes: (string | null)[] = [];
  const rows: GraphRow[] = [];
  const pending: { from: GraphRow; parent: string; lane: number }[] = [];

  ordered.forEach((commit, row) => {
    let lane = lanes.indexOf(commit.hash);
    if (lane === -1) {
      lane = lanes.indexOf(null);
      if (lane === -1) lane = lanes.push(null) - 1;
    }
    // Any other lanes waiting for this commit converge here and close.
    lanes.forEach((h, i) => {
      if (h === commit.hash && i !== lane) lanes[i] = null;
    });

    const node: GraphRow = { commit, row, lane };
    rows.push(node);

    commit.parents.forEach((parent, i) => {
      let parentLane: number;
      if (i === 0) {
        parentLane = lane;
        lanes[lane] = parent;
      } else {
        const existing = lanes.indexOf(parent);
        if (existing !== -1) parentLane = existing;
        else {
          parentLane = lanes.indexOf(null);
          if (parentLane === -1) parentLane = lanes.push(null) - 1;
          lanes[parentLane] = parent;
        }
      }
      pending.push({ from: node, parent, lane: parentLane });
    });
    if (commit.parents.length === 0) lanes[lane] = null;
    while (lanes.length && lanes[lanes.length - 1] === null) lanes.pop();
  });

  const byHash = new Map(rows.map((r) => [r.commit.hash, r]));
  const edges: GraphEdge[] = [];
  for (const p of pending) {
    const to = byHash.get(p.parent);
    if (to) edges.push({ from: p.from, to, lane: p.lane });
  }
  const laneCount = Math.max(1, ...rows.map((r) => r.lane + 1), ...edges.map((e) => e.lane + 1));
  return { rows, edges, laneCount };
}
