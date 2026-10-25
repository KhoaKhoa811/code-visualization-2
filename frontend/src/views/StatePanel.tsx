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
import type { CallsView } from '../trace/calls.ts';
import type { SlidingWindow } from '../trace/window.ts';
import { CallTreePanel } from './CallTreePanel.tsx';
import { CallLink } from './CallLink.tsx';
import { ComplexityPanel } from './ComplexityPanel.tsx';
import { NestedPanel } from './NestedPanel.tsx';
import { DpPanel, type DpView } from './DpPanel.tsx';
import type { ChildrenMapping } from '../trace/children.ts';
import type { ChainMapping } from '../trace/chains.ts';
import type { Row as ComplexityRow } from '../trace/complexity.ts';

type ArrayObject = TraceObject & { kind: 'array' };

/** Confirmed tree (Part 9b), heap (Part 9c) and list/stack/queue (Part 10b) mappings that fit this run. */
export function StatePanel({ part, view, trees = [], heaps = [], graphs = [], structs = [], pointers = new Map(), windows = new Map(), leaving, calls, complexity = [], childTrees = [], chains = [], modes = {}, dp = null }: {
  /** Part 23: the side column (variables, call stack, call tree, complexity) or the center (structures and objects). */
  part: 'side' | 'center';
  view: View; trees?: TreeMapping[]; heaps?: HeapMapping[]; graphs?: GraphMapping[]; structs?: StructMapping[]; pointers?: Map<string, Pointer[]>;
  /** Part 25: sliding windows over arrays and strings at the cursor. */
  windows?: Map<string, SlidingWindow[]>;
  /** 12d: frames the last step ended, drawn from the state before it while they leave. */
  leaving?: { frames: State['frames']; state: State };
  /** Part 13: how the calls up to the cursor relate (call tree, waiting lines, returned values). */
  calls?: CallsView;
  /** Part 15b: every method's estimate with what this run measured. */
  complexity?: ComplexityRow[];
  /** Part 19d: trees with children in a field, and arrays of chains (shown when detected). */
  childTrees?: ChildrenMapping[]; chains?: ChainMapping[];
  /** Part 19f: a deque shown as a stack or queue because it was detected so (the user's choice wins). */
  modes?: Record<string, 'stack' | 'queue'>;
  /** Part 20: tables filled and reused by dynamic programming. */
  dp?: DpView | null;
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
  if (part === 'side') return (
    <div className="state side-state">
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
    </div>
  );
  return (
    <div className="state">
      <LibraryPanel view={view} modes={modes} />
      <NestedPanel view={view} trees={childTrees} chains={chains} />
      <DpPanel view={view} dp={dp} />
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
              <ArrayBody array={array} state={state} focus={focus} depth={0} pointers={indices} all={pointers} look={look} windows={windows.get(array.objectId)}
                         heat={look === 'heat' ? range(state, rows) : undefined} />
            </Draggable>
          );
        })}
      </Panel>
      <StringsPanel view={view} pointers={pointers} windows={windows} />
    </div>
  );
}

/**
 * Cells of a one-dimensional array, or one row per element of an array of arrays. 12d: in a grid,
 * the grid's own index variables point at rows, and a variable that indexed rows points at cells
 * of the row it indexed last (`g[i][j]`: `i ▶` at row i, `▲ j` under that row).
 */
function ArrayBody({ array, state, focus, depth, pointers = [], all = new Map(), look, heat, windows = [] }: {
  array: ArrayObject; state: State; focus: Focus; depth: number; pointers?: Pointer[]; all?: Map<string, Pointer[]>;
  /** Part 18: bars (a number array) or heat colours (cells of a number grid, scaled by heat). */
  look?: ArrayLook; heat?: { min: number; max: number };
  /** Part 25: sliding windows over this array's cells. */
  windows?: SlidingWindow[];
}) {
  const active = focus.objectId === array.objectId;
  if (!array.elementType.endsWith('[]') || depth > 3) {
    const inside = pointers.filter(p => p.value >= 0 && p.value <= array.elements.length);
    // 18c: two arrows on the cells (lo and hi, left and right) shade the cells between them.
    const ends = inside.filter(p => p.value < array.elements.length).map(p => p.value);
    const between = ends.length === 2 && ends[0] !== ends[1] ? [Math.min(...ends), Math.max(...ends)] : null;
    const tallest = look === 'bars' ? Math.max(1, ...array.elements.map(e => Math.abs(numeric(e) ?? 0))) : 1;
    return (
      <div className={`cells${inside.length ? ' has-pointers' : ''}${windows.length ? ' has-window' : ''}${look === 'bars' ? ' bars' : ''}`} data-testid={look === 'bars' ? 'bars' : undefined}>
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
        {windows.length > 0 && <WindowLayer windows={windows} />}
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

/**
 * Part 25: a sliding window as a box around its cells, labelled with what it holds, placed from the
 * measured cells. The cell that entered at this step is marked +, the one that left is marked −.
 */
function WindowLayer({ windows }: { windows: SlidingWindow[] }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const [spots, setSpots] = useState('[]');
  useLayoutEffect(() => {
    const cells = anchor.current?.parentElement;
    if (!cells) return;
    const box = (i: number | null) => i === null ? null : cells.querySelector<HTMLElement>(`:scope > [data-index="${i}"]`);
    const placed = windows.map(w => {
      const a = box(w.lo), b = box(w.hi), inn = box(w.entered), out = box(w.left);
      if (!a || !b) return null;
      const spot = (el: HTMLElement | null) => el ? [el.offsetLeft + el.offsetWidth - 2, el.offsetTop - 2] : null; // the cell's top-right corner
      return { x: a.offsetLeft - 4, y: a.offsetTop - 4, w: b.offsetLeft + b.offsetWidth - a.offsetLeft + 8,
        h: Math.max(a.offsetTop + a.offsetHeight, b.offsetTop + b.offsetHeight) - a.offsetTop + 8, inn: spot(inn), out: spot(out) };
    });
    const json = JSON.stringify(placed);
    if (json !== spots) setSpots(json);
  });
  const at = JSON.parse(spots) as ({ x: number; y: number; w: number; h: number; inn: number[] | null; out: number[] | null } | null)[];
  return (
    <span ref={anchor} className="pointer-layer" aria-hidden="true">
      {windows.map((w, i) => {
        const s = at[i];
        return s && (
          <span key={`${w.label}-${i}`}>
            <span className="window-box" data-testid="window" data-lo={w.lo} data-hi={w.hi}
              style={{ transform: `translate(${s.x}px, ${s.y}px)`, width: s.w, height: s.h }}>
              <span className="window-label">{w.label} · size {w.hi - w.lo + 1}</span>
            </span>
            {s.inn && <span className="window-mark in" data-testid="window-in" style={{ transform: `translate(calc(${s.inn[0]}px - 50%), calc(${s.inn[1]}px - 50%))` }}>+</span>}
            {s.out && <span className="window-mark out" data-testid="window-out" style={{ transform: `translate(calc(${s.out[0]}px - 50%), calc(${s.out[1]}px - 50%))` }}>−</span>}
          </span>
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
function StringsPanel({ view, pointers, windows }: { view: View; pointers: Map<string, Pointer[]>; windows: Map<string, SlidingWindow[]> }) {
  const state = view.beforeEnd ?? view.state;
  const strings = [...state.objects.values()].filter((o): o is TraceObject & { kind: 'string' } => o.kind === 'string')
    .filter(o => namesOf(state, o.objectId).length > 0);
  if (strings.length === 0) return null;
  return (
    <Panel id="strings" label="Strings" title="Strings">
      {strings.map(s => {
        const text = stringText(s);
        const arrows = (pointers.get(s.objectId) ?? []).filter(p => p.value >= 0 && p.value <= s.utf16.length);
        const around = windows.get(s.objectId) ?? [];
        return (
          <div key={s.objectId} className="string-row" data-testid={`string-${namesOf(state, s.objectId)[0]}`}>
            <span className="name">{namesOf(state, s.objectId).join(' = ')}</span>{' '}
            {s.utf16.length === 0 || s.utf16.length > 60 ? JSON.stringify(text) : (
              <div className={`cells string-cells${arrows.length ? ' has-pointers' : ''}${around.length ? ' has-window' : ''}`}>
                {s.utf16.map((u, i) => (
                  <div className="cell char" key={i} data-testid="char" data-object={s.objectId} data-index={i}>
                    <div className="cell-value">{u === 32 ? '␣' : String.fromCharCode(u)}</div>
                    <div className="cell-index">{i}</div>
                  </div>
                ))}
                {arrows.length > 0 && <PointerLayer pointers={arrows} />}
                {around.length > 0 && <WindowLayer windows={around} />}
              </div>
            )}
          </div>
        );
      })}
    </Panel>
  );
}