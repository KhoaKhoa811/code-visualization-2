// Confirmed views of user-defined linked lists, stacks and queues (Part 10b). Lists are chains of
// nodes with their recorded next links (draggable; arrows follow). Stacks and queues show their
// storage array with the occupied slots, top or front/rear, and a circular queue as it sits in the
// array. Anything the recorded fields do not determine is said, not guessed.
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, namesOf, shortClass } from '../trace/format.ts';
import type { State, View } from '../trace/playback.ts';
import { projectList, projectQueue, projectStack, type ArrayView, type StructMapping } from '../trace/structures.ts';
import { Draggable, Panel } from './Draggable.tsx';

const GX = 96, GY = 64;

interface Line { key: string; d: string; repeat: boolean }

function ListView({ view, mapping }: { view: View; mapping: StructMapping & { kind: 'list' } }) {
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const roots = state.bindings.flatMap(b => (b.value.kind === 'ref' ? [b.value.objectId] : []));
  const { chains, conflicts } = projectList(state, mapping, roots);
  const container = useRef<HTMLDivElement>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const links: { from: string; to: string; repeat: boolean }[] = [];
  for (const c of chains) {
    c.nodes.forEach((n, i) => { if (i + 1 < c.nodes.length) links.push({ from: n.objectId, to: c.nodes[i + 1].objectId, repeat: false }); });
    if (c.end.kind === 'repeat') links.push({ from: c.nodes[c.nodes.length - 1].objectId, to: c.end.objectId, repeat: true });
  }
  useLayoutEffect(() => {
    const root = container.current;
    if (!root) return;
    const base = root.getBoundingClientRect();
    const box = (id: string) => root.querySelector<HTMLElement>(`[data-list-node="${id}"]`)?.getBoundingClientRect();
    const next: Line[] = [];
    for (const l of links) {
      const a = box(l.from), b = box(l.to);
      if (!a || !b) continue;
      const x1 = a.right - base.left, y1 = a.top + a.height / 2 - base.top;
      const forward = b.left >= a.right;
      const x2 = (forward ? b.left : b.left + b.width / 2) - base.left, y2 = (forward ? b.top + b.height / 2 : b.bottom) - base.top;
      next.push({ key: `${l.from}-${l.to}`, repeat: l.repeat,
        d: forward ? `M ${x1} ${y1} L ${x2} ${y2}` : `M ${x1} ${y1} C ${x1 + 40} ${y1 + 50}, ${x2} ${y2 + 40}, ${x2} ${y2}` });
    }
    setLines(prev => JSON.stringify(prev) === JSON.stringify(next) ? prev : next);
  });
  if (chains.length === 0) return null;
  const width = Math.max(...chains.map(c => c.nodes.length)) * GX + 70;
  return (
    <div className="struct-block" data-testid={`list-${shortClass(mapping.className)}`}>
      <div className="array-label">{shortClass(mapping.className)} <span className="muted">list · value {mapping.value} · next {mapping.next}</span></div>
      {conflicts.length > 0 && <p className="warning" data-testid="list-conflicts">Not a simple list here: {conflicts.join(', ')} (drawn as recorded).</p>}
      <div className="tree list-chains" ref={container} style={{ width, height: chains.length * GY + 20 }}>
        <svg className="graph-arrows" aria-hidden="true">
          <defs>
            <marker id="list-head" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
          </defs>
          {lines.map(l => <path key={l.key} d={l.d} className={l.repeat ? 'repeat' : undefined} markerEnd="url(#list-head)" />)}
        </svg>
        {chains.map((c, row) => c.nodes.map((n, i) => {
          const names = [...new Set(namesOf(state, n.objectId).filter(x => !x.includes('.')))].slice(0, 2);
          const hit = focus.objectId === n.objectId ? ` ${focus.access ?? ''}` : '';
          const last = i === c.nodes.length - 1;
          return (
            <Draggable id={`list:${n.objectId}`} key={n.objectId} className={`list-node${hit}`} data-list-node={n.objectId} data-testid="list-node"
                       style={{ left: i * GX, top: row * GY }} title={n.objectId}>
              <span className="tree-value">{formatValue(n.value, state.objects)}</span>
              {names.length > 0 && <span className="list-names">{names.join(' = ')}</span>}
              {last && c.end.kind === 'null' && <span className="list-end">null</span>}
              {last && c.end.kind === 'unknown' && <span className="list-end unknown">?</span>}
              {last && c.end.kind === 'other' && <span className="list-end">{formatValue(c.end.value, state.objects)}</span>}
            </Draggable>
          );
        }))}
      </div>
    </div>
  );
}

function Cells({ state, view, av, vertical, marks }: { state: State; view: View; av: ArrayView; vertical: boolean; marks: (i: number) => string }) {
  const focus = view.focus;
  const indices = (av.cells ?? []).map((_, i) => i);
  const order = vertical ? [...indices].reverse() : indices;
  const occupied = new Set(av.occupied);
  return (
    <div className={vertical ? 'stack-cells' : 'cells'}>
      {order.map(i => {
        const hit = av.storageId !== null && focus.objectId === av.storageId && focus.index === i ? ` ${focus.access}` : '';
        return (
          <div className={`cell${occupied.has(i) ? ' in-heap' : ' outside'}${hit}`} key={i} data-testid="struct-cell">
            <div className="cell-value">{formatValue(av.cells![i], state.objects)}</div>
            <div className="cell-index">{[String(i), marks(i)].filter(Boolean).join(' ')}</div>
          </div>
        );
      })}
    </div>
  );
}

function ArrayStructView({ view, mapping }: { view: View; mapping: StructMapping & { kind: 'stack' | 'queue' } }) {
  const state = view.beforeEnd ?? view.state;
  const views = mapping.kind === 'stack' ? projectStack(state, mapping) : projectQueue(state, mapping);
  if (views.length === 0) return null;
  return (
    <>
      {views.map(av => {
        const names = [...new Set(namesOf(state, av.objectId).filter(x => !x.includes('.')))].slice(0, 2);
        const logical = av.occupied.map(i => formatValue(av.cells![i], state.objects));
        const label = mapping.kind === 'stack'
          ? `stack · storage ${mapping.storage} · ${mapping.top} = ${mapping.topIs === 'count' ? 'size' : 'top index'}`
          : `queue · storage ${mapping.storage} · front ${mapping.front} · rear ${mapping.rear}${mapping.size ? ` · size ${mapping.size}` : ''}`;
        return (
          <Draggable id={`struct:${av.objectId}`} key={av.objectId} className="struct-block" data-testid={`${mapping.kind}-${names[0] ?? av.objectId}`}>
            <div className="array-label">{names.join(' = ') || shortClass(mapping.className)} <span className="muted">{shortClass(mapping.className)} {label}</span></div>
            {av.notes.map(n => <p className="warning" key={n} data-testid="struct-note">{n}</p>)}
            {av.cells && (
              <>
                <Cells state={state} view={view} av={av} vertical={mapping.kind === 'stack'}
                       marks={i => mapping.kind === 'stack' ? (i === av.top && av.occupied.length ? 'top' : '')
                         : [i === av.front && 'front', i === av.rear && (mapping.rearIs === 'next' ? 'rear (next free)' : 'rear')].filter(Boolean).join(' / ')} />
                <p className="muted" data-testid="struct-logical">
                  {mapping.kind === 'stack' ? 'bottom → top' : 'front → rear'}: {logical.length ? logical.join(', ') : 'empty'}
                </p>
              </>
            )}
          </Draggable>
        );
      })}
    </>
  );
}

export function StructPanel({ view, mappings }: { view: View; mappings: StructMapping[] }) {
  const state = view.beforeEnd ?? view.state;
  const present = mappings.filter(m => [...state.objects.values()].some(o => o.kind === 'object' && o.className === m.className));
  if (present.length === 0) return null;
  return (
    <Panel id="structures" label="Structures" title="Structures">
      {present.map(m => m.kind === 'list'
        ? <ListView key={m.className} view={view} mapping={m} />
        : <ArrayStructView key={m.className} view={view} mapping={m} />)}
    </Panel>
  );
}
