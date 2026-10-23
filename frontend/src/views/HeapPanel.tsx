// Arrays the user confirmed as heaps (Part 9c): the same array object as an indexed strip and
// as a tree, on the playback cursor. Indices outside the recorded boundary are the suffix
// outside the heap. Heap order is only observed: violations are listed, never repaired (AC-34).
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, namesOf } from '../trace/format.ts';
import type { View } from '../trace/playback.ts';
import { children, heapPositions, projectHeap, type HeapMapping } from '../trace/heap.ts';
import { Draggable, Panel } from './Draggable.tsx';

const DX = 50, DY = 60;

interface Line { key: string; x1: number; y1: number; x2: number; y2: number; bad: boolean }

function Heap({ view, mapping }: { view: View; mapping: HeapMapping }) {
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const heap = projectHeap(state, mapping);
  const container = useRef<HTMLDivElement>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const bad = new Set((heap?.violations ?? []).map(([p, c]) => `${p}-${c}`));
  const pos = heap ? heapPositions(mapping, heap.heap) : new Map<number, { x: number; y: number }>();
  const edges: [number, number][] = [];
  for (const i of pos.keys()) for (const c of children(mapping, i)) if (pos.has(c)) edges.push([i, c]);

  useLayoutEffect(() => {
    const root = container.current;
    if (!root) return;
    const base = root.getBoundingClientRect();
    const box = (i: number) => root.querySelector<HTMLElement>(`[data-heap-index="${i}"]`)?.getBoundingClientRect();
    const next: Line[] = [];
    for (const [p, c] of edges) {
      const a = box(p), b = box(c);
      if (!a || !b) continue;
      next.push({ key: `${p}-${c}`, bad: bad.has(`${p}-${c}`), x1: a.left + a.width / 2 - base.left, y1: a.bottom - base.top,
        x2: b.left + b.width / 2 - base.left, y2: b.top - base.top });
    }
    setLines(prev => JSON.stringify(prev) === JSON.stringify(next) ? prev : next);
  });

  if (!heap) {
    return <div className="heap-block muted" data-testid={`heap-${mapping.array}`}>{mapping.array} is not in scope at this step.</div>;
  }
  const { array, boundary } = heap;
  const active = focus.objectId === array.objectId;
  const inHeap = new Set(heap.heap);
  const width = Math.max(1, ...[...pos.values()].map(p => p.x + 1)) * DX + 10;
  const height = Math.max(0, ...[...pos.values()].map(p => p.y)) * DY + DY;
  const end = boundary ?? array.elements.length;
  return (
    <div className="heap-block" data-testid={`heap-${mapping.array}`} data-heap={array.objectId}>
      <div className="array-label">
        {[...new Set(namesOf(state, array.objectId).filter(n => !n.includes('[')))].slice(0, 2).join(' = ') || mapping.array}{' '}
        <span className="muted">
          {mapping.order}-heap, {mapping.base}-based · heap {boundary === null ? 'boundary unknown' : `indices ${mapping.base}..${end - 1}`}
          {mapping.size && ` (${mapping.size} as ${mapping.bound === 'count' ? 'size' : 'last index'})`}
        </span>
      </div>
      {heap.boundaryNote && <p className="muted" data-testid="heap-boundary-note">No boundary shown: {heap.boundaryNote}.</p>}
      <div className="cells heap-strip" data-testid="heap-strip">
        {array.elements.map((v, i) => {
          const role = i < mapping.base ? ' unused' : boundary === null ? '' : inHeap.has(i) ? ' in-heap' : ' outside';
          const hit = active && focus.index === i ? ` ${focus.access}` : '';
          return (
            <div className={`cell${role}${hit}${v.kind === 'unknown' ? ' unknown' : ''}`} key={i} data-testid="heap-cell">
              <div className="cell-value">{formatValue(v, state.objects)}</div>
              <div className="cell-index">{i}</div>
            </div>
          );
        })}
      </div>
      <p className={heap.violations.length ? 'warning' : 'muted'} data-testid="heap-order">
        {heap.violations.length
          ? `${mapping.order === 'max' ? 'Max' : 'Min'}-heap order does not hold at ${heap.violations.map(([p, c]) => `${p}→${c}`).join(', ')} (observed, not repaired).`
          : `${mapping.order === 'max' ? 'Max' : 'Min'}-heap order holds in the active heap (an observation, not a proof the algorithm is correct).`}
      </p>
      <div className="tree heap-tree" ref={container} style={{ width, height }}>
        <svg className="tree-edges" aria-hidden="true">
          {lines.map(l => <line key={l.key} x1={l.x1} y1={l.y1} x2={l.x2} y2={l.y2} className={l.bad ? 'repeat' : undefined} />)}
        </svg>
        {[...pos.entries()].map(([i, p]) => {
          const hit = active && focus.index === i ? ` ${focus.access}` : '';
          return (
            <Draggable id={`heap:${array.objectId}:${i}`} key={i} className={`tree-node${hit}`} data-heap-index={i} data-testid="heap-node"
                       style={{ left: p.x * DX, top: p.y * DY }} title={`index ${i}`}>
              <span className="tree-value">{formatValue(array.elements[i], state.objects)}</span>
              <span className="tree-names">[{i}]</span>
            </Draggable>
          );
        })}
      </div>
    </div>
  );
}

export function HeapPanel({ view, mappings }: { view: View; mappings: HeapMapping[] }) {
  if (mappings.length === 0) return null;
  return (
    <Panel id="heaps" label="Heaps" title="Heaps">
      {mappings.map(m => <Heap key={m.array} view={view} mapping={m} />)}
    </Panel>
  );
}
