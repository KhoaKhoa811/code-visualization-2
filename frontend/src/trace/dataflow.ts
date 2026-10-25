// Part 18a: what a step's array write was computed from. The reads are the recorded ARRAY_READ
// events of the same frame inside the write's own code (`dp[i][j] = dp[i - 1][j] + dp[i][j - 1]`),
// going back from the write until the frame runs code outside it. Calls inside the statement run
// in other frames and are passed over. Nothing is inferred beyond those recorded reads.
// Part 20a: the same walk also lists the calls the statement made (memo[n] = fib(n - 1) + fib(n - 2))
// with the results they returned.
import type { Playback, View } from './playback.ts';
import type { TraceEvent, Value } from './types.ts';

export interface Cell { objectId: string; index: number }

export interface Flow { target: Cell; sources: Cell[] }

export interface Origin {
  /** Cells read by the statement, in reading order (the written cell itself excluded). */
  sources: (Cell & { value: Value })[];
  /** Calls the statement made, in order, with the method, arguments and returned value. */
  calls: { methodId: string; args: Value[]; result: Value | null }[];
}

/** What the ARRAY_WRITE at events[at] was computed from. */
export function originOf(events: TraceEvent[], at: number): Origin {
  const write = events[at];
  const out: Origin = { sources: [], calls: [] };
  if (write.kind !== 'ARRAY_WRITE') return out;
  for (let i = at - 1; i >= 0; i--) {
    const e = events[i];
    if (!('step' in e)) continue;
    if (e.frameId !== write.frameId) continue; // inside a call made by the statement
    if (e.source.start < write.source.start || e.source.end > write.source.end) break;
    // A read that only fetches a row of a grid (dp[i - 1] in dp[i - 1][j]) is not a value it used.
    if (e.kind === 'ARRAY_READ' && e.data.value.kind !== 'ref' && !out.sources.some(s => s.objectId === e.data.objectId && s.index === e.data.index)
        && !(e.data.objectId === write.data.objectId && e.data.index === write.data.index)) {
      out.sources.unshift({ objectId: e.data.objectId, index: e.data.index, value: e.data.value });
    }
    if (e.kind === 'CALL') {
      const frame = e.data.frame.frameId;
      const ret = events.slice(i + 1, at).find(r => r.kind === 'RETURN' && r.frameId === frame);
      out.calls.unshift({ methodId: e.data.frame.methodId, args: e.data.parameters.map(p => p.value),
        result: ret && ret.kind === 'RETURN' ? ret.data.result : null });
    }
  }
  return out;
}

export function flowAt(playback: Playback, view: View): Flow | null {
  const write = view.event;
  if (!write || write.kind !== 'ARRAY_WRITE') return null;
  const { sources } = originOf(playback.trace.events, write.seq - 1);
  // Reading the same cell it writes (dp[i] += x) is not drawn as an arrow to itself.
  return { target: { objectId: write.data.objectId, index: write.data.index }, sources: sources.map(s => ({ objectId: s.objectId, index: s.index })) };
}
