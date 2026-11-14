/** cyrb53: fast, well-distributed 53-bit string hash (not cryptographic). */
export function cyrb53(str: string, seed = 0): number {
  let h1 = 0xdeadbeef ^ seed;
  let h2 = 0x41c6ce57 ^ seed;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

/** A SHA-256-shaped (64 hex chars) deterministic id, for mock data only. */
export function fakeSha256(input: string): string {
  // Each cyrb53 yields 53 bits; keep the low 48 bits (12 hex chars) so every digit is uniform.
  let out = '';
  for (let i = 0; out.length < 64; i++) out += (cyrb53(input, i * 7919) % 2 ** 48).toString(16).padStart(12, '0');
  return out.slice(0, 64);
}

/** mulberry32 PRNG: returns a function producing floats in [0, 1). */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Seeds a PRNG from (part of) a hex hash. */
export function rngFromHash(hash: string, salt = ''): () => number {
  return mulberry32(cyrb53(hash + salt));
}

export const short = (hash: string | null | undefined) => (hash ? hash.slice(0, 7) : '-------');
