// Objects of traced classes as boxes and their references as arrows (Part 8e).
// Layout is by recorded reference distance from variables only: no algorithm or
// structure is guessed. Shared objects appear once; cycles draw back-arrows.
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, namesOf, objectLabel } from '../trace/format.ts';
import type { State, View } from '../trace/playback.ts';
import type { TraceObject } from '../trace/types.ts';
import { Draggable, Panel } from './Draggable.tsx';

type ObjectNode = TraceObject & { kind: 'object' };
interface Arrow { key: string; d: string }

/** Breadth-first columns from objects that variables or array elements refer to. */
function columns(state: State): ObjectNode[][] {
  const objects = [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object');
  const depth = new Map<string, number>();
  const queue: string[] = [];
  const root = (id: string) => {
    if (state.objects.get(id)?.kind === 'object' && !depth.has(id)) { depth.set(id, 0); queue.push(id); }
  };
  for (const b of state.bindings) if (b.value.kind === 'ref') root(b.value.objectId);
  for (const o of state.objects.values()) {
    if (o.kind === 'array') for (const e of o.elements) if (e.kind === 'ref') root(e.objectId);
  }
  while (queue.length) {
    const id = queue.shift()!;
    const node = state.objects.get(id) as ObjectNode;
    for (const f of node.fields) {
      if (f.value.kind === 'ref' && state.objects.get(f.value.objectId)?.kind === 'object' && !depth.has(f.value.objectId)) {
        depth.set(f.value.objectId, depth.get(id)! + 1);
        queue.push(f.value.objectId);
      }
    }
  }
  const out: ObjectNode[][] = [];
  for (const o of objects) {
    const d = depth.get(o.objectId) ?? 0; // captured but no longer reachable: first column
    (out[d] ??= []).push(o);
  }
  return out.filter(Boolean);
}

export function ObjectGraph({ view }: { view: View }) {
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const container = useRef<HTMLDivElement>(null);
  const [arrows, setArrows] = useState<Arrow[]>([]);
  const layout = columns(state);

  useLayoutEffect(() => {
    const root = container.current;
    if (!root) return;
    const base = root.getBoundingClientRect();
    const next: Arrow[] = [];
    root.querySelectorAll<HTMLElement>('[data-ref-to]').forEach(dot => {
      const target = root.querySelector<HTMLElement>(`[data-object="${dot.dataset.refTo}"]`);
      if (!target) return;
      const a = dot.getBoundingClientRect(), b = target.getBoundingClientRect();
      const x1 = a.left + a.width / 2 - base.left, y1 = a.top + a.height / 2 - base.top;
      const forward = b.left > a.right;
      const x2 = (forward ? b.left : b.right) - base.left, y2 = b.top + 14 - base.top;
      const d = forward
        ? `M ${x1} ${y1} C ${x1 + 40} ${y1}, ${x2 - 40} ${y2}, ${x2} ${y2}`
        : `M ${x1} ${y1} C ${x1 + 60} ${y1 + 30}, ${x2 + 60} ${y2 - 30}, ${x2} ${y2}`; // back-edge or self-reference
      next.push({ key: `${dot.dataset.from}-${dot.dataset.refTo}`, d });
    });
    setArrows(prev => JSON.stringify(prev) === JSON.stringify(next) ? prev : next);
  });

  if (layout.length === 0) return null;
  return (
    <Panel id="objects" label="Objects" title="Objects">
      <div className="graph" ref={container} data-testid="object-graph">
        <svg className="graph-arrows" aria-hidden="true">
          <defs>
            <marker id="arrow-head" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
          </defs>
          {arrows.map(a => <path key={a.key} d={a.d} markerEnd="url(#arrow-head)" />)}
        </svg>
        {layout.map((column, c) => (
          <div className="graph-column" key={c}>
            {column.map(o => {
              const names = namesOf(state, o.objectId);
              const allocated = focus.objectId === o.objectId && focus.access === 'allocate' || focus.touched?.includes(o.objectId);
              return (
                <Draggable id={`object:${o.objectId}`} className={`object${allocated ? ' allocated' : ''}`} key={o.objectId} data-object={o.objectId} data-box={o.objectId}
                     data-testid={`object-${names[0] ?? o.objectId}`}>
                  <div className="object-label" title={names.join(' = ')}>
                    {objectLabel(o)}{names.length > 0 && <span className="muted"> {names.slice(0, 2).join(' = ')}</span>}
                  </div>
                  {o.fields.map(f => {
                    const hit = focus.objectId === o.objectId && focus.field === f.name ? ` ${focus.access}` : '';
                    const target = f.value.kind === 'ref' && state.objects.get(f.value.objectId)?.kind === 'object' ? f.value.objectId : null;
                    return (
                      <div className={`field${hit}${f.value.kind === 'unknown' ? ' unknown' : ''}`} key={f.name} data-testid={`field-${f.name}`}>
                        <span className="field-name">{f.name}</span>
                        {target
                          ? <span className="ref-dot" data-ref-to={target} data-from={`${o.objectId}.${f.name}`} title={formatValue(f.value, state.objects)} />
                          : <span className="field-value">{formatValue(f.value, state.objects)}</span>}
                      </div>
                    );
                  })}
                </Draggable>
              );
            })}
          </div>
        ))}
      </div>
    </Panel>
  );
}
