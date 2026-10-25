// Frames, bindings and arrays at the playback cursor. Rendering is chosen by
// recorded data shape only (bindings, arrays, arrays of arrays), never by algorithm.
import { arrayType, formatValue, methodName, namesOf, stringText } from '../trace/format.ts';
import type { Focus, State, View } from '../trace/playback.ts';
import type { TraceObject } from '../trace/types.ts';
import { ObjectGraph } from './ObjectGraph.tsx';
import { LibraryPanel } from './LibraryPanel.tsx';
import { Draggable, Panel } from './Draggable.tsx';
import { TreePanel } from './TreePanel.tsx';
import type { TreeMapping } from '../trace/tree.ts';
import { HeapPanel } from './HeapPanel.tsx';
import { GraphPanel } from './GraphPanel.tsx';
import type { GraphMapping } from '../trace/graph.ts';
import type { HeapMapping } from '../trace/heap.ts';
import { StructPanel } from './StructPanel.tsx';
import type { StructMapping } from '../trace/structures.ts';
import { latestRows, type Pointer } from '../trace/pointers.ts';
import { useLayoutEffect, useRef, useState } from 'react';
import { numeric } from '../trace/format.ts';
import { setLook, type ArrayLook } from './layout.ts';
import { useLayout } from './Draggable.tsx';
import type { Flow } from '../trace/dataflow.ts';
import type { CallsView } from '../trace/calls.ts';
import { CallTreePanel } from './CallTreePanel.tsx';
import { CallLink } from './CallLink.tsx';
import { ComplexityPanel } from './ComplexityPanel.tsx';
import { NestedPanel } from './NestedPanel.tsx';
import type { ChildrenMapping } from '../trace/children.ts';
import type { ChainMapping } from '../trace/chains.ts';
import type { Row as ComplexityRow } from '../trace/complexity.ts';

type ArrayObject = TraceObject & { kind: 'array' };

/** Confirmed tree (Part 9b), heap (Part 9c) and list/stack/queue (Part 10b) mappings that fit this run. */
export function StatePanel({ view, trees = [], heaps = [], graphs = [], structs = [], pointers = new Map(), leaving, calls, complexity = [], flow = null, childTrees = [], chains = [], modes = {} }: {
  view: View; trees?: TreeMapping[]; heaps?: HeapMapping[]; graphs?: GraphMapping[]; structs?: StructMapping[]; pointers?: Map<string, Pointer[]>;
  /** 12d: frames the last step ended, drawn from the state before it while they leave. */
  leaving?: { frames: State['frames']; state: State };
  /** Part 13: how the calls up to the cursor relate (call tree, waiting lines, returned values). */
  calls?: CallsView;
  /** Part 15b: every method's estimate with what this run measured. */
  complexity?: ComplexityRow[];
  /** Part 18a: the cells this step's array write was computed from. */
  flow?: Flow | null;
  /** Part 19d: trees with children in a field, and arrays of chains (shown when detected). */
  childTrees?: ChildrenMapping[]; chains?: ChainMapping[];
  /** Part 19f: a deque shown as a stack or queue because it was detected so (the user's choice wins). */
  modes?: Record<string, 'stack' | 'queue'>;
}) {
  const { layout, update } = useLayout();
  // After main returns, show the recorded values it ended with, labelled as such.
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const arrays = [...state.objects.values()].filter((o): o is ArrayObject => o.kind === 'array');
  // Arrays reached only through another array (rows of a grid) are drawn inside it.
  const nested = new Set(arrays.flatMap(a => a.elements.flatMap(e => (e.kind === 'ref' ? [e.objectId] : []))));
  const bound = new Set(state.bindings.flatMap(b => (b.value.kind === 'ref' ? [b.value.objectId] : [])));
  const topLevel = arrays.filter(a => bound.has(a.objectId) || !nested.has(a.objectId));
  return (
    <div className="state">
      <Panel id="variables" label="Call stack" title={state.frames.length > 1 ? `Call stack (${state.frames.length} frames)` : 'Variables'}>
        {view.beforeEnd && <p className="muted" data-testid="ended-note">main has returned; showing the values it ended with.</p>}
        {state.frames.length === 0 && !leaving?.frames.length && <p className="muted">No active frame.</p>}
        {leaving?.frames.map(frame => (
          <div className="frame leaving" key={`leaving-${frame.frameId}`} data-testid="frame-leaving" aria-hidden="true">
            <div className="frame-name">{methodName(frame.methodId)} <span className="badge">returned</span></div>
            <table className="bindings">
              <tbody>
                {leaving.state.bindings.filter(b => b.frameId === frame.frameId).map(b => (
                  <tr key={b.bindingId}>
                    <td className="type">{b.javaType.replace(/\b(java\.lang|java\.util)\./g, '').replace(/\bMain\./g, '')}</td>
                    <td className="name">{b.name}</td>
                    <td className="value">{formatValue(b.value, leaving.state.objects)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
        {/* Innermost (running) frame first; each invocation has its own variables, even with the same names. */}
        {[...state.frames].reverse().map((frame, depth) => (
          <div className={`frame${depth === 0 ? ' active' : ''}`} key={frame.frameId} data-testid="frame" data-frame={frame.frameId}>
            <div className="frame-name" title={frame.methodId}>
              {methodName(frame.methodId)}{frame.via && <span className="muted"> via {methodName(frame.via)}</span>}{depth === 0 && state.frames.length > 1 && <span className="badge">running</span>}
            </div>
            {calls && <CallLink frameId={frame.frameId} calls={calls} state={state} />}
            <table className="bindings">
              <tbody>
                {state.bindings.filter(b => b.frameId === frame.frameId).map(b => (
                  <tr key={b.bindingId} className={focus.bindingId === b.bindingId || focus.touched?.includes(b.bindingId) ? 'changed' : undefined} data-testid={`var-${b.name}`} data-binding={b.bindingId}>
                    <td className="type" title={b.javaType}>{b.javaType.replace(/\b(java\.lang|java\.util)\./g, '').replace(/\bMain\./g, '')}</td>
                    <td className="name">{b.name}</td>
                    <td className="value">{formatValue(b.value, state.objects)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </Panel>
      {calls && <CallTreePanel calls={calls} state={state} />}
      <ComplexityPanel rows={complexity} />
      <LibraryPanel view={view} modes={modes} />
      <NestedPanel view={view} trees={childTrees} chains={chains} />
      <TreePanel view={view} mappings={trees} />
      <StructPanel view={view} mappings={structs} />
      <HeapPanel view={view} mappings={heaps} />
      <GraphPanel view={view} mappings={graphs} pointers={pointers} />
      <ObjectGraph view={view} />
      <Panel id="arrays" label="Arrays" title="Arrays">
        {topLevel.length === 0 && <p className="muted">No arrays yet.</p>}
        {topLevel.map(array => {
          const names = namesOf(state, array.objectId);
          const active = focus.objectId === array.objectId;
          const indices = pointers.get(array.objectId) ?? [];
          // A grid's row arrow has no place past the last row (12d); a cell arrow may sit just past the last cell.
          const grid = array.elementType.endsWith('[]');
          const outside = indices.filter(p => p.value < 0 || p.value > array.elements.length || grid && p.value === array.elements.length);
          // Part 18: a number grid may be drawn with heat colours, a number array with bars (the user's choice, by name).
          const name = names.find(n => !n.includes('[') && !n.includes('.'));
          const canLook: ArrayLook | null = !name ? null : grid ? (NUMBERS.has(array.elementType.slice(0, -2)) ? 'heat' : null)
            : NUMBERS.has(array.elementType) ? 'bars' : null;
          const look = canLook && name && layout.looks[name] === canLook ? canLook : undefined;
          const rows = grid ? array.elements.flatMap(e => {
            const r = e.kind === 'ref' ? state.objects.get(e.objectId) : undefined;
            return r?.kind === 'array' ? [r.objectId] : [];
          }) : [];
          const inBox = flow && [array.objectId, ...rows].includes(flow.target.objectId) ? flow : null;
          return (
            <Draggable id={`array:${array.objectId}`} data-box={array.objectId} className={`array${active && focus.access === 'allocate' || focus.touched?.includes(array.objectId) ? ' allocated' : ''}`} key={array.objectId}
                 data-testid={`array-${names[0] ?? array.objectId}`}>
              <div className="array-label">
                {names.length ? names.join(' = ') : '(temporary)'} <span className="muted">{arrayType(array)}</span>
                {active && focus.access === 'length' && <span className="badge">length</span>}
                {outside.map(p => <span className="pointer-outside" key={p.frameId + p.name} data-testid={`pointer-${p.name}`}>{p.name} = {p.value} (outside)</span>)}
                {canLook && name && (
                  <button className={`look-toggle${look ? ' on' : ''}`} aria-pressed={!!look} data-testid={`look-${canLook}`}
                          onClick={() => update(l => setLook(l, name, look ? null : canLook))}>{canLook === 'heat' ? 'heat colours' : 'bars'}</button>
                )}
              </div>
              <ArrayBody array={array} state={state} focus={focus} depth={0} pointers={indices} all={pointers} look={look}
                         heat={look === 'heat' ? range(state, rows) : undefined} />
              {inBox && inBox.sources.length > 0 && <FlowLayer flow={inBox} />}
            </Draggable>
          );
        })}
      </Panel>
      <StringsPanel view={view} pointers={pointers} />
    </div>
  );
}

/**
 * Cells of a one-dimensional array, or one row per element of an array of arrays. 12d: in a grid,
 * the grid's own index variables point at rows, and a variable that indexed rows points at cells
 * of the row it indexed last (`g[i][j]`: `i ▶` at row i, `▲ j` under that row).
 */
function ArrayBody({ array, state, focus, depth, pointers = [], all = new Map(), look, heat }: {
  array: ArrayObject; state: State; focus: Focus; depth: number; pointers?: Pointer[]; all?: Map<string, Pointer[]>;
  /** Part 18: bars (a number array) or heat colours (cells of a number grid, scaled by heat). */
  look?: ArrayLook; heat?: { min: number; max: number };
}) {
  const active = focus.objectId === array.objectId;
  if (!array.elementType.endsWith('[]') || depth > 3) {
    const inside = pointers.filter(p => p.value >= 0 && p.value <= array.elements.length);
    // 18c: two arrows on the cells (lo and hi, left and right) shade the cells between them.
    const ends = inside.filter(p => p.value < array.elements.length).map(p => p.value);
    const between = ends.length === 2 && ends[0] !== ends[1] ? [Math.min(...ends), Math.max(...ends)] : null;
    const tallest = look === 'bars' ? Math.max(1, ...array.elements.map(e => Math.abs(numeric(e) ?? 0))) : 1;
    return (
      <div className={`cells${inside.length ? ' has-pointers' : ''}${look === 'bars' ? ' bars' : ''}`} data-testid={look === 'bars' ? 'bars' : undefined}>
        {array.elements.length === 0 && <span className="muted">empty</span>}
        {array.elements.map((element, index) => {
          const hit = active && focus.index === index ? ` ${focus.access}` : '';
          const n = numeric(element);
          const ranged = between && index >= between[0] && index <= between[1] ? ' in-range' : '';
          const warmth = heat && n !== null && heat.max > heat.min ? (n - heat.min) / (heat.max - heat.min) : heat && n !== null ? 0.5 : null;
          return (
            <div className={`cell${hit}${element.kind === 'unknown' ? ' unknown' : ''}${ranged}${look === 'bars' ? ' bar' : ''}`} key={index} data-testid="cell"
              data-object={array.objectId} data-index={index}
              style={warmth !== null ? { background: `color-mix(in srgb, #e0763a ${Math.round(12 + warmth * 70)}%, var(--panel))` } : undefined}>
              {look === 'bars' && <div className={`bar-fill${n !== null && n < 0 ? ' negative' : ''}`} data-testid="bar"
                style={{ height: `${Math.max(3, Math.round(Math.abs(n ?? 0) / tallest * 96))}px` }} />}
              <div className="cell-value">{formatValue(element, state.objects)}</div>
              <div className="cell-index">{index}</div>
            </div>
          );
        })}
        {inside.length > 0 && <PointerLayer pointers={inside} />}
      </div>
    );
  }
  const rowArrows = pointers.filter(p => p.value >= 0 && p.value < array.elements.length);
  const columns = latestRows(array, all);
  return (
    <div className={`rows${rowArrows.length ? ' has-pointers' : ''}`} data-testid="rows"
      style={rowArrows.length ? { paddingLeft: `${Math.max(...rowArrows.map(p => p.name.length)) * 8 + 24}px` } : undefined}>
      {array.elements.length === 0 && <span className="muted">empty</span>}
      {rowArrows.length > 0 && <RowPointerLayer pointers={rowArrows} />}
      {array.elements.map((element, index) => {
        const row = element.kind === 'ref' ? state.objects.get(element.objectId) : undefined;
        const hit = active && focus.index === index ? ` ${focus.access}` : '';
        // A row shared with an earlier index or another variable is the same array, not a copy.
        const sharedWith = element.kind === 'ref'
          ? array.elements.findIndex(e => e.kind === 'ref' && e.objectId === element.objectId) : -1;
        const alsoBound = row ? state.bindings.filter(b => b.value.kind === 'ref' && b.value.objectId === row.objectId).map(b => b.name) : [];
        return (
          <div className={`row${hit}${focus.objectId === row?.objectId && focus.access === 'allocate' ? ' allocated' : ''}`} key={index} data-testid="row" data-row-index={index}>
            <span className="row-index">{index}</span>
            {row?.kind === 'array'
              ? <ArrayBody array={row} state={state} focus={focus} depth={depth + 1} all={all} heat={heat}
                  pointers={sharedWith >= 0 && sharedWith < index ? [] : columns.get(row.objectId) ?? []} />
              : <span className={`cell-null${element.kind === 'unknown' ? ' unknown' : ''}`}>{formatValue(element, state.objects)}</span>}
            {sharedWith >= 0 && sharedWith < index && <span className="badge" title="Both elements refer to one array">same row as [{sharedWith}]</span>}
            {alsoBound.length > 0 && <span className="badge" title="A variable refers to this row">= {alsoBound.join(', ')}</span>}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Part 11b: index arrows under the cells, placed from the cells' measured positions after layout
 * (cells differ in width and wrap). Keyed by variable, so an arrow slides when its value changes.
 * A value equal to the length points just past the last cell, where a loop like i < a.length ends.
 */
function PointerLayer({ pointers }: { pointers: Pointer[] }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const [spots, setSpots] = useState('{}');
  useLayoutEffect(() => {
    const cells = anchor.current?.parentElement;
    if (!cells) return;
    const placed: Record<string, [number, number]> = {};
    const stacked = new Map<number, number>();
    for (const p of pointers) {
      const cell = cells.querySelector<HTMLElement>(`:scope > [data-index="${p.value}"]`);
      const last = cells.querySelector<HTMLElement>(':scope > [data-index]:last-of-type');
      const x = cell ? cell.offsetLeft + cell.offsetWidth / 2 : last ? last.offsetLeft + last.offsetWidth + 18 : 18;
      const base = cell ?? last;
      const level = stacked.get(p.value) ?? 0;
      stacked.set(p.value, level + 1);
      placed[p.frameId + p.name] = [x, (base ? base.offsetTop + base.offsetHeight : 0) + 2 + level * 15];
    }
    const json = JSON.stringify(placed);
    if (json !== spots) setSpots(json);
  });
  const at = JSON.parse(spots) as Record<string, [number, number]>;
  return (
    <span ref={anchor} className="pointer-layer" aria-hidden="true">
      {pointers.map(p => {
        const spot = at[p.frameId + p.name];
        return spot && (
          <span key={p.frameId + p.name} className="pointer" data-testid={`pointer-${p.name}`} data-value={p.value}
            style={{ transform: `translate(calc(${spot[0]}px - 50%), ${spot[1]}px)` }}>▲ {p.name}</span>
        );
      })}
    </span>
  );
}

const NUMBERS = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'char']);

/** Part 18a: the smallest and largest number in a grid's rows, for heat colours (one scale for the grid). */
function range(state: State, rows: string[]): { min: number; max: number } {
  let min = Infinity, max = -Infinity;
  for (const id of rows) {
    const r = state.objects.get(id);
    if (r?.kind !== 'array') continue;
    for (const e of r.elements) { const n = numeric(e); if (n !== null) { min = Math.min(min, n); max = Math.max(max, n); } }
  }
  return min === Infinity ? { min: 0, max: 0 } : { min, max };
}

/**
 * Part 18a: arrows from the cells a write read to the cell it wrote, in the array's box, placed
 * from the measured cells (rows and cells vary in size and wrap).
 */
function FlowLayer({ flow }: { flow: Flow }) {
  const anchor = useRef<SVGSVGElement>(null);
  const [paths, setPaths] = useState<string[]>([]);
  useLayoutEffect(() => {
    const box = anchor.current?.parentElement;
    if (!box) return;
    const base = box.getBoundingClientRect();
    const at = (c: { objectId: string; index: number }) => {
      const cell = box.querySelector<HTMLElement>(`[data-object="${c.objectId}"][data-index="${c.index}"]`);
      if (!cell) return null;
      const r = cell.getBoundingClientRect();
      return { x: r.left + r.width / 2 - base.left, y: r.top + r.height / 2 - base.top };
    };
    const to = at(flow.target);
    const next = to ? flow.sources.flatMap(s => {
      const from = at(s);
      if (!from) return [];
      // Bend sideways so arrows between neighbours in one row stay visible.
      const mx = (from.x + to.x) / 2 + (to.y - from.y) * 0.25, my = (from.y + to.y) / 2 - Math.max(18, Math.abs(to.x - from.x) * 0.25);
      return [`M ${from.x} ${from.y} Q ${mx} ${my} ${to.x} ${to.y}`];
    }) : [];
    if (JSON.stringify(next) !== JSON.stringify(paths)) setPaths(next);
  });
  return (
    <svg ref={anchor} className="flow-layer" aria-hidden="true" data-testid="flow-arrows" data-count={paths.length}>
      <defs>
        <marker id="flow-head" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" />
        </marker>
      </defs>
      {paths.map((d, i) => <path key={i} d={d} markerEnd="url(#flow-head)" />)}
    </svg>
  );
}

/** 12d: row arrows to the left of a grid's rows, placed from the measured rows; they slide like cell arrows. */
function RowPointerLayer({ pointers }: { pointers: Pointer[] }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const [spots, setSpots] = useState('{}');
  useLayoutEffect(() => {
    const rows = anchor.current?.parentElement;
    if (!rows) return;
    const placed: Record<string, number> = {};
    const stacked = new Map<number, number>();
    for (const p of pointers) {
      const row = rows.querySelector<HTMLElement>(`:scope > [data-row-index="${p.value}"]`);
      if (!row) continue;
      const level = stacked.get(p.value) ?? 0;
      stacked.set(p.value, level + 1);
      placed[p.frameId + p.name] = row.offsetTop + row.offsetHeight / 2 + level * 14;
    }
    const json = JSON.stringify(placed);
    if (json !== spots) setSpots(json);
  });
  const at = JSON.parse(spots) as Record<string, number>;
  return (
    <span ref={anchor} className="pointer-layer" aria-hidden="true">
      {pointers.map(p => {
        const y = at[p.frameId + p.name];
        return y !== undefined && (
          <span key={p.frameId + p.name} className="pointer row-pointer" data-testid={`pointer-${p.name}`} data-value={p.value}
            style={{ transform: `translate(0, calc(${y}px - 50%))` }}>{p.name} ▶</span>
        );
      })}
    </span>
  );
}

/** 18e: strings named by variables, as character boxes; `s.charAt(i)` makes `i` an arrow, as for arrays. Long strings stay text. */
function StringsPanel({ view, pointers }: { view: View; pointers: Map<string, Pointer[]> }) {
  const state = view.beforeEnd ?? view.state;
  const strings = [...state.objects.values()].filter((o): o is TraceObject & { kind: 'string' } => o.kind === 'string')
    .filter(o => namesOf(state, o.objectId).length > 0);
  if (strings.length === 0) return null;
  return (
    <Panel id="strings" label="Strings" title="Strings">
      {strings.map(s => {
        const text = stringText(s);
        const arrows = (pointers.get(s.objectId) ?? []).filter(p => p.value >= 0 && p.value <= s.utf16.length);
        return (
          <div key={s.objectId} className="string-row" data-testid={`string-${namesOf(state, s.objectId)[0]}`}>
            <span className="name">{namesOf(state, s.objectId).join(' = ')}</span>{' '}
            {s.utf16.length === 0 || s.utf16.length > 60 ? JSON.stringify(text) : (
              <div className={`cells string-cells${arrows.length ? ' has-pointers' : ''}`}>
                {s.utf16.map((u, i) => (
                  <div className="cell char" key={i} data-testid="char" data-object={s.objectId} data-index={i}>
                    <div className="cell-value">{u === 32 ? '␣' : String.fromCharCode(u)}</div>
                    <div className="cell-index">{i}</div>
                  </div>
                ))}
                {arrows.length > 0 && <PointerLayer pointers={arrows} />}
              </div>
            )}
          </div>
        );
      })}
    </Panel>
  );
}