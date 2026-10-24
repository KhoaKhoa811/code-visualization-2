// Part 18a: what a step's array write was computed from. The reads are the recorded ARRAY_READ
// events of the same frame inside the write's own code (`dp[i][j] = dp[i - 1][j] + dp[i][j - 1]`),
// going back from the write until the frame runs code outside it. Calls inside the statement run
// in other frames and are passed over. Nothing is inferred beyond those recorded reads.
import type { Playback, View } from './playback.ts';

export interface Cell { objectId: string; index: number }

export interface Flow { target: Cell; sources: Cell[] }

export function flowAt(playback: Playback, view: View): Flow | null {
  const write = view.event;
  if (!write || write.kind !== 'ARRAY_WRITE') return null;
  const events = playback.trace.events;
  const sources: Cell[] = [];
  for (let i = write.seq - 2; i >= 0; i--) {
    const e = events[i];
    if (!('step' in e)) continue;
    if (e.frameId !== write.frameId) continue; // a call inside the statement
    if (e.source.start < write.source.start || e.source.end > write.source.end) break;
    // A read that only fetches a row of a grid (dp[i - 1] in dp[i - 1][j]) is not a value it used.
    if (e.kind === 'ARRAY_READ' && e.data.value.kind !== 'ref' && !sources.some(s => s.objectId === e.data.objectId && s.index === e.data.index)) {
      sources.unshift({ objectId: e.data.objectId, index: e.data.index });
    }
  }
  // Reading the same cell it writes (dp[i] += x) is not drawn as an arrow to itself.
  const target = { objectId: write.data.objectId, index: write.data.index };
  return { target, sources: sources.filter(s => s.objectId !== target.objectId || s.index !== target.index) };
}