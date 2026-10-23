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
import type { HeapMapping } from '../trace/heap.ts';
import { StructPanel } from './StructPanel.tsx';
import type { StructMapping } from '../trace/structures.ts';
import type { Pointer } from '../trace/pointers.ts';
import { useLayoutEffect, useRef, useState } from 'react';

type ArrayObject = TraceObject & { kind: 'array' };

/** Confirmed tree (Part 9b), heap (Part 9c) and list/stack/queue (Part 10b) mappings that fit this run. */
export function StatePanel({ view, trees = [], heaps = [], structs = [], pointers = new Map() }: {
  view: View; trees?: TreeMapping[]; heaps?: HeapMapping[]; structs?: StructMapping[]; pointers?: Map<string, Pointer[]>;
}) {
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
        {state.frames.length === 0 && <p className="muted">No active frame.</p>}
        {/* Innermost (running) frame first; each invocation has its own variables, even with the same names. */}
        {[...state.frames].reverse().map((frame, depth) => (
          <div className={`frame${depth === 0 ? ' active' : ''}`} key={frame.frameId} data-testid="frame">
            <div className="frame-name" title={frame.methodId}>
              {methodName(frame.methodId)}{frame.via && <span className="muted"> via {methodName(frame.via)}</span>}{depth === 0 && state.frames.length > 1 && <span className="badge">running</span>}
            </div>
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
      <LibraryPanel view={view} />
      <TreePanel view={view} mappings={trees} />
      <StructPanel view={view} mappings={structs} />
      <HeapPanel view={view} mappings={heaps} />
      <ObjectGraph view={view} />
      <Panel id="arrays" label="Arrays" title="Arrays">
        {topLevel.length === 0 && <p className="muted">No arrays yet.</p>}
        {topLevel.map(array => {
          const names = namesOf(state, array.objectId);
          const active = focus.objectId === array.objectId;
          const indices = pointers.get(array.objectId) ?? [];
          const outside = indices.filter(p => p.value < 0 || p.value > array.elements.length);
          return (
            <Draggable id={`array:${array.objectId}`} className={`array${active && focus.access === 'allocate' || focus.touched?.includes(array.objectId) ? ' allocated' : ''}`} key={array.objectId}
                 data-testid={`array-${names[0] ?? array.objectId}`}>
              <div className="array-label">
                {names.length ? names.join(' = ') : '(temporary)'} <span className="muted">{arrayType(array)}</span>
                {active && focus.access === 'length' && <span className="badge">length</span>}
                {outside.map(p => <span className="pointer-outside" key={p.frameId + p.name} data-testid={`pointer-${p.name}`}>{p.name} = {p.value} (outside)</span>)}
              </div>
              <ArrayBody array={array} state={state} focus={focus} depth={0} pointers={indices} />
            </Draggable>
          );
        })}
      </Panel>
      <StringsPanel view={view} />
    </div>
  );
}

/** Cells of a one-dimensional array, or one row per element of an array of arrays. */
function ArrayBody({ array, state, focus, depth, pointers = [] }: { array: ArrayObject; state: State; focus: Focus; depth: number; pointers?: Pointer[] }) {
  const active = focus.objectId === array.objectId;
  if (!array.elementType.endsWith('[]') || depth > 3) {
    const inside = pointers.filter(p => p.value >= 0 && p.value <= array.elements.length);
    return (
      <div className={`cells${inside.length ? ' has-pointers' : ''}`}>
        {array.elements.length === 0 && <span className="muted">empty</span>}
        {array.elements.map((element, index) => {
          const hit = active && focus.index === index ? ` ${focus.access}` : '';
          return (
            <div className={`cell${hit}${element.kind === 'unknown' ? ' unknown' : ''}`} key={index} data-testid="cell"
              data-object={array.objectId} data-index={index}>
              <div className="cell-value">{formatValue(element, state.objects)}</div>
              <div className="cell-index">{index}</div>
            </div>
          );
        })}
        {inside.length > 0 && <PointerLayer pointers={inside} />}
      </div>
    );
  }
  return (
    <div className="rows" data-testid="rows">
      {array.elements.length === 0 && <span className="muted">empty</span>}
      {array.elements.map((element, index) => {
        const row = element.kind === 'ref' ? state.objects.get(element.objectId) : undefined;
        const hit = active && focus.index === index ? ` ${focus.access}` : '';
        // A row shared with an earlier index or another variable is the same array, not a copy.
        const sharedWith = element.kind === 'ref'
          ? array.elements.findIndex(e => e.kind === 'ref' && e.objectId === element.objectId) : -1;
        const alsoBound = row ? state.bindings.filter(b => b.value.kind === 'ref' && b.value.objectId === row.objectId).map(b => b.name) : [];
        return (
          <div className={`row${hit}${focus.objectId === row?.objectId && focus.access === 'allocate' ? ' allocated' : ''}`} key={index} data-testid="row">
            <span className="row-index">{index}</span>
            {row?.kind === 'array'
              ? <ArrayBody array={row} state={state} focus={focus} depth={depth + 1} />
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

function StringsPanel({ view }: { view: View }) {
  const state = view.beforeEnd ?? view.state;
  const strings = [...state.objects.values()].filter((o): o is TraceObject & { kind: 'string' } => o.kind === 'string')
    .filter(o => namesOf(state, o.objectId).length > 0);
  if (strings.length === 0) return null;
  return (
    <Panel id="strings" label="Strings" title="Strings">
      {strings.map(s => (
        <div key={s.objectId} className="string-row">
          <span className="name">{namesOf(state, s.objectId).join(' = ')}</span> {JSON.stringify(stringText(s))}
        </div>
      ))}
    </Panel>
  );
}
