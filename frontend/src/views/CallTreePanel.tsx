// Part 13a: the call tree. Every call made up to the cursor stays as a node under the frame that
// made it, with its arguments and, once it ended, what it returned (or that it threw). The path
// of frames still running is highlighted; the innermost is the one running now.
import { formatValue, methodName } from '../trace/format.ts';
import type { State } from '../trace/playback.ts';
import type { CallNode, CallsView } from '../trace/calls.ts';
import type { ReactNode } from 'react';
import { Panel } from './Draggable.tsx';

/** At most this many calls are drawn; the running path always is, then the latest calls. */
export const MAX_CALLS = 200;

type Node = CallNode & { ended: boolean };

export function callLabel(node: CallNode, objects: State['objects']): string {
  return `${methodName(node.methodId)}(${node.args.map(a => formatValue(a.value, objects)).join(', ')})`;
}

export function CallTreePanel({ calls, state }: { calls: CallsView; state: State }) {
  if (calls.nodes.length <= 1) return null; // main alone: nothing to relate
  const running = new Set(state.frames.map(f => f.frameId));
  const current = state.frames.at(-1)?.frameId;
  // Keep the running path, then the most recent calls, up to the bound.
  let shown = calls.nodes;
  if (shown.length > MAX_CALLS) {
    const keep = new Set([calls.nodes[0].frameId, ...calls.nodes.filter(n => running.has(n.frameId)).map(n => n.frameId)]);
    for (let i = calls.nodes.length - 1; i >= 0 && keep.size < MAX_CALLS; i--) keep.add(calls.nodes[i].frameId);
    shown = calls.nodes.filter(n => keep.has(n.frameId));
  }
  const hidden = calls.nodes.length - shown.length;
  const visible = new Set(shown.map(n => n.frameId));
  const children = new Map<string | null, Node[]>();
  for (const n of shown) {
    // A call whose caller is not drawn hangs under main, which is always drawn.
    const parent = n.callerFrameId !== null && !visible.has(n.callerFrameId) ? shown[0].frameId : n.callerFrameId;
    children.set(parent, [...(children.get(parent) ?? []), n]);
  }
  const draw = (node: Node, depth: number): ReactNode => {
    const kids = children.get(node.frameId) ?? [];
    const status = node.frameId === current ? 'current' : running.has(node.frameId) ? 'running' : node.threw ? 'threw' : 'returned';
    return (
      <li key={node.frameId} className={`call-node ${status}`} data-testid="call-node" data-frame={node.frameId} data-status={status}>
        <span className="call-label" title={node.methodId}>
          {node.callerFrameId === null ? 'main' : callLabel(node, state.objects)}
          {node.via && <span className="muted"> via {methodName(node.via)}</span>}
          {node.callerFrameId !== null && node.ended && !node.threw && node.result && <span className="call-result" data-testid="call-result"> → {formatValue(node.result, state.objects)}</span>}
          {node.callerFrameId !== null && node.ended && !node.threw && !node.result && <span className="muted"> done</span>}
          {node.threw && <span className="call-threw"> threw</span>}
          {status === 'current' && <span className="badge">running</span>}
        </span>
        {kids.length > 0 && <ul>{kids.map(k => draw(k, depth + 1))}</ul>}
      </li>
    );
  };
  const root = shown[0];
  return (
    <Panel id="calls" label="Call tree" title={`Call tree (${calls.nodes.length - 1} call${calls.nodes.length === 2 ? '' : 's'})`} testId="call-tree">
      {hidden > 0 && <p className="muted" data-testid="calls-hidden">{hidden} earlier calls are not drawn.</p>}
      <ul className="call-tree">{draw(root, 0)}</ul>
    </Panel>
  );
}
