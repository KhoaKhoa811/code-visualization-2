// Part 20: tables a program fills and reuses (dynamic programming), as a full matrix at the
// cursor. Every cell says whether it is computed yet (an initial fill or default is not), in which
// order it was computed and how often its stored value was read again. The step's own write shows
// the cells and calls it came from; a read of a computed cell shows the reuse. Clicking a cell shows
// what it came from and which later cells used it. Built from recorded reads and writes only.
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, methodName, namesOf } from '../trace/format.ts';
import type { State, View } from '../trace/playback.ts';
import { cellKey, type DpLog, type DpTable } from '../trace/dp.ts';
import type { Cell } from '../trace/dataflow.ts';
import type { Value } from '../trace/types.ts';
import { Draggable, Panel } from './Draggable.tsx';

export interface DpView { tables: Map<string, DpTable>; log: DpLog }

interface Slot { cell: Cell; value: Value | null; row: number; col: number }

/** The cells as rows and columns: a grid's rows, one row for an array, a map's integer keys in order. */
function slots(state: State, t: DpTable): Slot[][] {
  if (t.kind === 'map') {
    const m = state.objects.get(t.holderId);
    const keys = new Set<number>([...t.cells.keys()].map(k => Number(k.split('#')[1])));
    if (m?.kind === 'map') for (const e of m.entries) if (e.key.kind === 'int' || e.key.kind === 'long') keys.add(Number(e.key.value));
    const value = (k: number) => m?.kind === 'map' ? m.entries.find(e => (e.key.kind === 'int' || e.key.kind === 'long') && Number(e.key.value) === k)?.value ?? null : null;
    return [[...keys].sort((a, b) => a - b).map((k, col) => ({ cell: { objectId: t.holderId, index: k }, value: value(k), row: 0, col }))];
  }
  return t.cellHolders.map((id, row) => {
    const a = state.objects.get(id);
    return a?.kind === 'array' ? a.elements.map((v, col) => ({ cell: { objectId: id, index: col }, value: v, row, col })) : [];
  });
}

function Table({ view, table, log }: { view: View; table: DpTable; log: DpLog }) {
  const state = view.beforeEnd ?? view.state;
  const grid = slots(state, table);
  const name = namesOf(state, table.holderId).find(n => !n.includes('[') && !n.includes('.')) ?? 'table';
  const label = (c: Cell): string => {
    if (table.kind === 'map') return `${name}.get(${c.index})`;
    if (table.kind === 'array') return `${name}[${c.index}]`;
    return `${name}[${table.cellHolders.indexOf(c.objectId)}][${c.index}]`;
  };
  const inTable = (c: Cell) => table.cellHolders.includes(c.objectId);
  // A cell of another array is named by its variable (weight[1]) when one refers to it.
  const any = (c: Cell): string => {
    if (inTable(c)) return label(c);
    const other = namesOf(state, c.objectId).find(n => !n.includes('.'));
    return other ? `${other}[${c.index}]` : 'another array';
  };
  const [selected, setSelected] = useState<string | null>(null);

  // This step: a computation stored into the table, or a read of one of its cells.
  const e = view.event;
  const write = e ? log.writes.find(w => w.step === e.step && inTable(w.cell)) : undefined;
  const read = e ? log.reads.find(r => r.step === e.step && inTable(r.cell)) : undefined;
  const readCell = read ? table.cells.get(cellKey(read.cell)) : undefined;
  const hit = read && readCell?.computed && readCell.computed.step < read.step;

  const sel = selected ? table.cells.get(selected) : undefined;
  const selCell = selected ? grid.flat().find(s => cellKey(s.cell) === selected)?.cell : undefined;
  const arrows: { from: Cell; to: Cell; kind: 'from' | 'to' }[] = [];
  if (write && !write.initial) for (const s of write.sources) if (inTable(s)) arrows.push({ from: s, to: write.cell, kind: 'from' });
  if (sel?.computed && selCell) {
    for (const s of sel.computed.sources) if (inTable(s)) arrows.push({ from: s, to: selCell, kind: 'from' });
    for (const u of sel.usedBy) arrows.push({ from: selCell, to: u, kind: 'to' });
  }
  const sources = new Set(write ? write.sources.map(cellKey) : []);
  const callText = (c: { methodId: string; args: Value[]; result: Value | null }) =>
    `${methodName(c.methodId)}(${c.args.map(a => formatValue(a, state.objects)).join(', ')})${c.result ? ` = ${formatValue(c.result, state.objects)}` : ''}`;

  return (
    <Draggable id={`dp:${table.holderId}`} className="dp-block" data-testid={`dp-${name}`}>
      <div className="array-label">{name} <span className="muted">
        {table.style === 'bottom-up' ? 'filled from its own cells (bottom-up)' : 'filled by recursive calls, then reused (top-down)'}
        {' · '}{table.computed} computed · {table.reuses} reuse{table.reuses === 1 ? '' : 's'}</span></div>
      <div className="dp-legend muted"><span className="dp-swatch computed" /> computed <span className="dp-swatch empty" /> not yet
        <span className="dp-badge">↻n</span> reused n times <span className="dp-order">#n</span> order computed</div>
      <div className="dp-grid">
        {grid.map((row, r) => (
          <div className="dp-row" key={r}>
            {table.kind === 'grid' && <span className="row-index">{r}</span>}
            {row.map(s => {
              const k = cellKey(s.cell), info = table.cells.get(k);
              const status = info?.computed ? 'computed' : info?.initial ? 'initial' : 'empty';
              const now = write && cellKey(write.cell) === k && !write.initial ? ' now-write' : read && cellKey(read.cell) === k ? (hit ? ' now-reuse' : ' now-check') : '';
              const rel = sources.has(k) ? ' now-source' : selected === k ? ' selected' : sel?.computed?.sources.some(x => cellKey(x) === k) ? ' sel-from'
                : sel?.usedBy.some(x => cellKey(x) === k) ? ' sel-to' : '';
              return (
                <button className={`dp-cell ${status}${now}${rel}`} key={k} data-dp-cell={k} data-testid="dp-cell" data-state={status}
                        title={`${label(s.cell)}${status === 'empty' ? ' (not computed: its default value)' : status === 'initial' ? ' (not computed: its initial fill)' : ''}`}
                        onClick={() => setSelected(selected === k ? null : k)}>
                  {info?.order && <span className="dp-order">#{info.order}</span>}
                  {info && info.reuses > 0 && <span className="dp-badge" data-testid="dp-reuses">↻{info.reuses}</span>}
                  <span className="dp-value">{!s.value ? '·' : formatValue(s.value, state.objects)}</span>
                  <span className="cell-index">{table.kind === 'map' ? s.cell.index : s.col}</span>
                </button>
              );
            })}
          </div>
        ))}
        <DpArrows arrows={arrows} />
      </div>
      <p className="dp-now" data-testid="dp-now">
        {write && !write.initial && <>step {write.step}: <code>{write.code}</code> stores <b>{formatValue(write.value, state.objects)}</b> in {label(write.cell)}
          {write.sources.length > 0 && <> from {write.sources.map(s => `${any(s)} = ${formatValue(s.value, state.objects)}`).join(', ')}</>}
          {write.calls.length > 0 && <> from {write.calls.map(callText).join(', ')}</>}</>}
        {read && hit && <>step {read.step}: reuses {label(read.cell)} = <b>{formatValue(read.value, state.objects)}</b>, stored at step {readCell!.computed!.step}</>}
        {read && !hit && <>step {read.step}: {label(read.cell)} is not computed yet (it holds {formatValue(read.value, state.objects)})</>}
        {!write && !read && <span className="muted">This step does not touch the table. Click a cell to see where it came from.</span>}
      </p>
      {sel && selCell && (
        <p className="dp-detail" data-testid="dp-detail">
          <b>{label(selCell)}</b>{' '}
          {sel.computed ? <>= {formatValue(sel.computed.value, state.objects)}: computed at step {sel.computed.step} by <code>{sel.computed.code}</code>
            {sel.computed.sources.length > 0 && <> from {sel.computed.sources.map(s => `${any(s)} = ${formatValue(s.value, state.objects)}`).join(', ')}</>}
            {sel.computed.calls.length > 0 && <> from {sel.computed.calls.map(callText).join(', ')}</>}
            ; reused {sel.reuses} time{sel.reuses === 1 ? '' : 's'}{sel.usedBy.length > 0 && <>; used by {sel.usedBy.map(label).join(', ')}</>}.</>
            : 'is not computed yet.'}
        </p>
      )}
    </Draggable>
  );
}

/** Arrows between cells, placed from the measured cells: what a cell came from, and what used it. */
function DpArrows({ arrows }: { arrows: { from: Cell; to: Cell; kind: 'from' | 'to' }[] }) {
  const anchor = useRef<SVGSVGElement>(null);
  const [paths, setPaths] = useState<{ d: string; kind: string }[]>([]);
  useLayoutEffect(() => {
    const box = anchor.current?.parentElement;
    if (!box) return;
    const base = box.getBoundingClientRect();
    const at = (c: Cell) => {
      const el = box.querySelector<HTMLElement>(`[data-dp-cell="${cellKey(c)}"]`);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.left + r.width / 2 - base.left, y: r.top + r.height / 2 - base.top };
    };
    const next = arrows.flatMap(a => {
      const p = at(a.from), q = at(a.to);
      if (!p || !q) return [];
      const mx = (p.x + q.x) / 2 + (q.y - p.y) * 0.2, my = (p.y + q.y) / 2 - Math.max(14, Math.abs(q.x - p.x) * 0.2);
      return [{ d: `M ${p.x} ${p.y} Q ${mx} ${my} ${q.x} ${q.y}`, kind: a.kind }];
    });
    if (JSON.stringify(next) !== JSON.stringify(paths)) setPaths(next);
  });
  return (
    <svg ref={anchor} className="flow-layer dp-arrows" aria-hidden="true" data-testid="dp-arrows" data-count={paths.length}>
      <defs>
        <marker id="dp-head-from" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" /></marker>
        <marker id="dp-head-to" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" /></marker>
      </defs>
      {paths.map((p, i) => <path key={i} d={p.d} className={p.kind} markerEnd={`url(#dp-head-${p.kind})`} />)}
    </svg>
  );
}

export function DpPanel({ view, dp }: { view: View; dp: DpView | null }) {
  if (!dp || dp.tables.size === 0) return null;
  return (
    <Panel id="dp" label="DP tables" title="DP tables" testId="dp">
      {[...dp.tables.values()].map(t => <Table key={t.holderId} view={view} table={t} log={dp.log} />)}
    </Panel>
  );
}
