// Part 15b (specs/complexity.md): estimates from the code's shape (trace.complexity, 15a) next to
// what this run measured. Measured counts are facts of the whole recorded run: how many times
// each method was called and how many steps ran in it, including the methods it called.
import type { Playback } from './playback.ts';
import type { ComplexityEstimate } from './types.ts';

export interface Measured { calls: number; steps: number; own: number }

export interface Row { estimate: ComplexityEstimate; measured: Measured | null }

/** Calls and steps per methodId over the whole recorded run. */
export function measure(playback: Playback): Map<string, Measured> {
  const out = new Map<string, Measured>();
  const method = new Map<string, string>(); // frameId -> methodId
  const caller = new Map<string, string | null>();
  for (const f of playback.trace.initialState?.frames ?? []) { method.set(f.frameId, f.methodId); caller.set(f.frameId, f.callerFrameId); }
  const entry = (id: string) => { let m = out.get(id); if (!m) { m = { calls: 0, steps: 0, own: 0 }; out.set(id, m); } return m; };
  for (const id of method.values()) entry(id).calls++;
  for (const e of playback.trace.events) {
    if (e.kind === 'CALL') {
      method.set(e.data.frame.frameId, e.data.frame.methodId);
      caller.set(e.data.frame.frameId, e.data.frame.callerFrameId);
      entry(e.data.frame.methodId).calls++;
    }
    if (!('step' in e) || !e.frameId) continue;
    // A step counts for its own method and once for every method above it on the stack (recursion counts once).
    const seen = new Set<string>();
    for (let f: string | null | undefined = e.frameId; f; f = caller.get(f)) {
      const id = method.get(f);
      if (!id || seen.has(id)) continue;
      seen.add(id);
      const m = entry(id);
      m.steps++;
      if (f === e.frameId) m.own++;
    }
  }
  return out;
}

const key = (e: ComplexityEstimate) => [e.growth.exponentialBase, Number(e.growth.polynomial), e.growth.logarithmic];

/** Slowest growth first is last: O(1) < O(log n) < O(n) < O(n log n) < O(n²) < O(2ⁿ). */
export function byGrowth(a: ComplexityEstimate, b: ComplexityEstimate): number {
  const x = key(a), y = key(b);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i];
  return a.name.localeCompare(b.name);
}

export function rows(playback: Playback | null, estimates: ComplexityEstimate[]): Row[] {
  const counts = playback?.available ? measure(playback) : new Map<string, Measured>();
  return [...estimates].sort(byGrowth).map(estimate => ({ estimate, measured: counts.get(estimate.methodId) ?? null }));
}

/** The bound as shown: approximate estimates get "≈". */
export const shown = (e: ComplexityEstimate) => `${e.approximate ? '≈ ' : ''}${e.bound}`;
