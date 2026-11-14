import { rngFromHash } from './hash';

/**
 * Phenotype = the visible creature derived from serialization state.
 *
 * - The lineage ROOT hash fixes the body plan (species), so a DAIMon stays recognisable as it evolves.
 * - The HEAD hash drives coloration, markings and traits, so every commit subtly mutates it.
 */

export interface Element {
  name: string;
  color: string;
  glow: string;
}

export const ELEMENTS: Element[] = [
  { name: 'Volt', color: '#eab308', glow: 'rgba(234,179,8,0.45)' },
  { name: 'Glitch', color: '#ec4899', glow: 'rgba(236,72,153,0.45)' },
  { name: 'Cipher', color: '#06b6d4', glow: 'rgba(6,182,212,0.45)' },
  { name: 'Toxin', color: '#10b981', glow: 'rgba(16,185,129,0.45)' },
  { name: 'Void', color: '#8b5cf6', glow: 'rgba(139,92,246,0.45)' },
];

const NATURES = ['Chaotic', 'Patient', 'Impulsive', 'Playful', 'Stoic', 'Curious', 'Feral', 'Balanced'];
const DRIVES = ['Curiosity', 'Integrity', 'Exploration', 'Speed', 'Synthesis', 'Mischief', 'Order', 'Hunger'];

export interface BodyPlan {
  radius: number;
  lobes: number;
  lobeDepth: number;
  wobble: number;
  wobbleFreq: number;
  phase: number;
  squash: number;
  eyes: number;
  eyeSize: number;
  horns: number;
  hornStyle: 'spike' | 'antenna' | 'curl';
  mouth: 'grin' | 'fangs' | 'o' | 'line';
  feet: number;
  tail: boolean;
}

export interface Markings {
  pattern: 'spots' | 'stripes' | 'circuit' | 'none';
  count: number;
  seed: number;
}

export interface Phenotype {
  element: Element;
  secondary: Element;
  body: BodyPlan;
  markings: Markings;
  traits: { complexity: number; nature: number; drive: number };
  natureLabel: string;
  driveLabel: string;
  generation: number;
}

const pick = <T,>(rng: () => number, items: readonly T[]): T => items[Math.floor(rng() * items.length)]!;
const range = (rng: () => number, min: number, max: number) => min + rng() * (max - min);
const int = (rng: () => number, min: number, max: number) => Math.floor(range(rng, min, max + 1));

export function derivePhenotype(rootHash: string, headHash: string, generation: number, fileCount = 1): Phenotype {
  const species = rngFromHash(rootHash, 'species');
  const mutation = rngFromHash(headHash, 'mutation');

  const element = pick(species, ELEMENTS);
  const body: BodyPlan = {
    radius: range(species, 28, 34),
    lobes: int(species, 3, 7),
    lobeDepth: range(species, 0.04, 0.16),
    wobble: range(species, 0.02, 0.07),
    wobbleFreq: int(species, 2, 5),
    phase: range(species, 0, Math.PI * 2),
    squash: range(species, 0.82, 1.08),
    eyes: pick(species, [1, 2, 2, 2, 3]),
    eyeSize: range(species, 4.2, 7.5),
    horns: pick(species, [0, 1, 2, 2, 3]),
    hornStyle: pick(species, ['spike', 'antenna', 'curl'] as const),
    mouth: pick(species, ['grin', 'fangs', 'o', 'line'] as const),
    feet: pick(species, [0, 2, 2, 3, 4]),
    tail: species() > 0.55,
  };

  // Mutations drift with every commit.
  const secondary = pick(
    mutation,
    ELEMENTS.filter((e) => e.name !== element.name),
  );
  body.lobeDepth *= range(mutation, 0.85, 1.2);
  body.eyeSize *= range(mutation, 0.92, 1.1);

  const complexityBase = range(mutation, 25, 70);
  const traits = {
    complexity: clamp(Math.round(complexityBase + generation * 6 + fileCount * 3)),
    nature: clamp(Math.round(range(mutation, 10, 98))),
    drive: clamp(Math.round(range(mutation, 15, 99))),
  };

  return {
    element,
    secondary,
    body,
    markings: {
      pattern: pick(mutation, ['spots', 'stripes', 'circuit', 'none'] as const),
      count: int(mutation, 3, 7),
      seed: Math.floor(mutation() * 1e9),
    },
    traits,
    natureLabel: NATURES[Math.floor((traits.nature / 101) * NATURES.length)]!,
    driveLabel: DRIVES[Math.floor((traits.drive / 101) * DRIVES.length)]!,
    generation,
  };
}

const clamp = (n: number) => Math.max(1, Math.min(100, n));

/** Closed, smooth blob outline for the body plan, centred at (cx, cy). */
export function bodyPath(body: BodyPlan, cx: number, cy: number): string {
  const n = 64;
  const pts: [number, number][] = [];
  for (let i = 0; i < n; i++) {
    const t = (i / n) * Math.PI * 2;
    const r =
      body.radius *
      (1 + body.lobeDepth * Math.sin(body.lobes * t + body.phase) + body.wobble * Math.sin(body.wobbleFreq * t * 1.5));
    pts.push([cx + r * Math.cos(t), cy + r * Math.sin(t) * body.squash]);
  }
  // Quadratic smoothing through midpoints.
  const mid = (a: [number, number], b: [number, number]) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2] as const;
  const first = mid(pts[n - 1]!, pts[0]!);
  let d = `M${first[0].toFixed(2)},${first[1].toFixed(2)}`;
  for (let i = 0; i < n; i++) {
    const p = pts[i]!;
    const m = mid(p, pts[(i + 1) % n]!);
    d += ` Q${p[0].toFixed(2)},${p[1].toFixed(2)} ${m[0].toFixed(2)},${m[1].toFixed(2)}`;
  }
  return d + 'Z';
}
