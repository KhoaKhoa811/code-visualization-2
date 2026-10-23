// Parts 13b and 13c: on a frame card, the line the frame is waiting on, with each call in it
// marked: still running (the call it waits for), or filled in with the value it returned, which
// stays until the frame goes on with its next step (where it uses the value).
import type { ReactNode } from 'react';
import { callLine, type CallNode, type CallsView } from '../trace/calls.ts';
import { formatValue } from '../trace/format.ts';
import type { State } from '../trace/playback.ts';
import { callLabel } from './CallTreePanel.tsx';

export function CallLink({ frameId, calls, state }: { frameId: string; calls: CallsView; state: State }) {
  const waiting = calls.waitingOn.get(frameId);
  const received = calls.received.get(frameId) ?? [];
  const marked: { node: CallNode; done: boolean }[] = [...received.map(node => ({ node, done: true })), ...(waiting ? [{ node: waiting, done: false }] : [])];
  if (marked.length === 0) return null;
  const text = calls.text;
  // One line per source line; the calls on it are marked in place.
  const lines = new Map<string, { line: string; marks: { at: number; length: number; node: CallNode; done: boolean }[] }>();
  for (const m of marked) {
    const { line, at } = callLine(text, m.node);
    const key = `${m.node.callStart - at}`;
    const entry = lines.get(key) ?? { line, marks: [] };
    entry.marks.push({ at, length: m.node.callText.length, node: m.node, done: m.done });
    lines.set(key, entry);
  }
  return (
    <div className="call-link" data-testid="call-link">
      {[...lines.values()].map(({ line, marks }, i) => {
        const parts: ReactNode[] = [];
        let from = 0;
        const apart: typeof marks = [];
        for (const m of [...marks].sort((a, b) => a.at - b.at)) {
          // A call inside another marked call, or over several lines, is listed after the line.
          if (m.at < from || m.at + m.length > line.length) { apart.push(m); continue; }
          parts.push(line.slice(from, m.at));
          parts.push(
            <mark key={m.node.frameId} className={m.done ? 'call-filled' : 'call-waiting'} data-testid={m.done ? 'call-filled' : 'call-waiting'}>
              {line.slice(m.at, m.at + m.length)}
              <span className="call-value">{m.done ? ` = ${m.node.result ? formatValue(m.node.result, state.objects) : 'done'}` : ` ⏳ ${callLabel(m.node, state.objects)}`}</span>
            </mark>,
          );
          from = m.at + m.length;
        }
        parts.push(line.slice(from));
        return (
          <div className="call-line" key={i}>
            <span className="muted">{marks.some(m => !m.done) ? 'waits at ' : 'received '}</span><code>{parts}</code>
            {apart.map(m => (
              <span key={m.node.frameId} className={`badge ${m.done ? 'call-filled' : 'call-waiting'}`} data-testid={m.done ? 'call-filled' : 'call-waiting'}>
                {m.node.callText}{m.done ? ` = ${m.node.result ? formatValue(m.node.result, state.objects) : 'done'}` : ' ⏳'}
              </span>
            ))}
          </div>
        );
      })}
    </div>
  );
}
