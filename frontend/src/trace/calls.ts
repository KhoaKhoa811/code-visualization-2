// Part 13: how calls relate. Built only from recorded CALL, RETURN and FRAME_END events up to
// the cursor: each call is a node under the frame that made it, with its arguments and, once it
// ended, its result (or that it threw). Nothing is inferred and no future event is read.
import type { Playback, View } from './playback.ts';
import type { Value } from './types.ts';

export interface CallNode {
  frameId: string;
  methodId: string;
  /** The frame that made the call; null for main. */
  callerFrameId: string | null;
  /** The library method that ran this function (a lambda or Main::helper), if any. */
  via?: string;
  /** Parameter values the call received. */
  args: { name: string; value: Value }[];
  /** The call's own code in the caller (`factorial(n - 1)`), and where it is. */
  callText: string;
  callStart: number;
  /** Event indexes: the CALL, and the RETURN or unwinding FRAME_END that ended it. */
  callIndex: number;
  endIndex?: number;
  result?: Value | null;
  threw?: boolean;
  /** The first later event of the caller that is not another call: where the caller goes on. */
  resumeIndex?: number;
}

const cache = new WeakMap<Playback, CallNode[]>();

/** Every call of the run in call order, with the facts that end it (filtered by cursor in callsAt). */
function allCalls(playback: Playback): CallNode[] {
  const known = cache.get(playback);
  if (known) return known;
  const text = playback.trace.source.text;
  const nodes: CallNode[] = [];
  const byFrame = new Map<string, CallNode>();
  const main = playback.trace.initialState?.frames[0];
  if (main) {
    const root: CallNode = { frameId: main.frameId, methodId: main.methodId, callerFrameId: null, args: [], callText: '', callStart: -1, callIndex: -1 };
    nodes.push(root);
    byFrame.set(root.frameId, root);
  }
  // Caller frame -> ended calls whose value it has not used yet. A value is used by the caller's
  // first later step whose code contains the call (`fib(n - 1) + fib(n - 2)`, `int r = f(x)`,
  // `return f(n - 1);`, an enclosing call `g(f(x))`), or is left behind once the caller runs code
  // past the call's line (a call made as a statement). Steps evaluating the next argument
  // (`n - 2`) do neither.
  const waiting = new Map<string, CallNode[]>();
  const lineEnd = (at: number) => { const end = text.indexOf('\n', at); return end < 0 ? text.length : end; };
  const settle = (frameId: string, start: number, end: number, index: number, leaving: boolean) => {
    const pending = waiting.get(frameId);
    if (!pending) return;
    const left = pending.filter(n => {
      const used = leaving || start <= n.callStart && end >= n.callStart + n.callText.length || start >= lineEnd(n.callStart);
      if (used) n.resumeIndex = index;
      return !used;
    });
    if (left.length) waiting.set(frameId, left); else waiting.delete(frameId);
  };
  playback.trace.events.forEach((e, index) => {
    if ('step' in e && e.frameId) settle(e.frameId, e.source.start, e.source.end, index, false);
    if (e.kind === 'CALL') {
      const node: CallNode = {
        frameId: e.data.frame.frameId, methodId: e.data.frame.methodId, callerFrameId: e.data.frame.callerFrameId,
        via: e.data.frame.via ?? undefined, args: e.data.parameters.map(p => ({ name: p.name, value: p.value })),
        callText: text.slice(e.source.start, e.source.end), callStart: e.source.start, callIndex: index,
      };
      nodes.push(node);
      byFrame.set(node.frameId, node);
      return;
    }
    if (e.kind === 'RETURN' || e.kind === 'FRAME_END') {
      const frameId = e.kind === 'RETURN' ? e.frameId : e.data.frameId;
      if (e.kind === 'FRAME_END') settle(frameId, 0, 0, index, true); // the caller itself ended
      const node = byFrame.get(frameId);
      if (!node || node.endIndex !== undefined) return;
      node.endIndex = index;
      if (e.kind === 'RETURN') node.result = e.data.result;
      else node.threw = e.data.reason === 'unwind';
      if (node.callerFrameId) waiting.set(node.callerFrameId, [...(waiting.get(node.callerFrameId) ?? []), node]);
    }
  });  cache.set(playback, nodes);
  return nodes;
}

export interface CallsView {
  /** The run's source text, where the calls' code is. */
  text: string;
  /** Calls made up to the cursor, in call order (main first). */
  nodes: (CallNode & { ended: boolean })[];
  /** Per frame still running: the call it is waiting on, if any. */
  waitingOn: Map<string, CallNode>;
  /** Per caller frame: ended calls whose results it has received and not yet gone on from. */
  received: Map<string, CallNode[]>;
}

/** The call relationships at a view's cursor: the events applied are exactly those before boundary(cursor). */
export function callsAt(playback: Playback, view: View): CallsView {
  const applied = playback.available ? playback.boundary(view.cursor) : 0;
  const currentIndex = view.event ? view.event.seq - 1 : -1;
  const nodes = allCalls(playback).filter(n => n.callIndex < applied)
    .map(n => ({ ...n, ended: n.endIndex !== undefined && n.endIndex < applied }));
  const waitingOn = new Map<string, CallNode>();
  const received = new Map<string, CallNode[]>();
  for (const n of nodes) {
    if (!n.callerFrameId) continue;
    if (!n.ended) { waitingOn.set(n.callerFrameId, n); continue; }
    // Shown until the caller goes on, and on the step where it does (it uses the value there).
    const pending = n.resumeIndex === undefined || n.resumeIndex >= applied || n.resumeIndex === currentIndex;
    if (pending && !n.threw) received.set(n.callerFrameId, [...(received.get(n.callerFrameId) ?? []), n]);
  }
  return { text: playback.trace.source.text, nodes, waitingOn, received };
}

/** The source line holding a call, trimmed, with the call's position inside it. */
export function callLine(text: string, node: CallNode): { line: string; at: number } {
  const from = text.lastIndexOf('\n', node.callStart - 1) + 1;
  const to = text.indexOf('\n', node.callStart);
  const raw = text.slice(from, to < 0 ? text.length : to);
  const lead = raw.length - raw.trimStart().length;
  return { line: raw.trim(), at: node.callStart - from - lead };
}
