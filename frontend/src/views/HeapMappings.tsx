// Confirming an array as a heap (Part 9c). Nothing suggests this automatically: calling an
// array a heap is the user's interpretation (evidence-based suggestions are part 10c). The
// mapping names variables, so it is checked against each run's recorded variables.
import { useState } from 'react';
import { heapProblem, heapVariables, type HeapMapping } from '../trace/heap.ts';
import type { Trace } from '../trace/types.ts';

interface Props {
  trace: Trace;
  mappings: HeapMapping[];
  onConfirm: (mapping: HeapMapping, replacing?: string) => void;
  onRemove: (array: string) => void;
}

function Editor({ start, trace, onConfirm, onCancel }: { start: HeapMapping; trace: Trace; onConfirm: (m: HeapMapping) => void; onCancel: () => void }) {
  const [m, setM] = useState(start);
  const vars = heapVariables(trace);
  const problem = heapProblem(m, trace);
  return (
    <div className="mapping-editor" data-testid="heap-editor">
      <label className="mapping-field">array{' '}
        <select aria-label="Heap array" value={m.array} onChange={e => setM({ ...m, array: e.target.value })}>
          {vars.arrays.map(a => <option key={a} value={a}>{a}</option>)}
        </select>
      </label>
      <label className="mapping-field">indexing{' '}
        <select aria-label="Heap indexing" value={m.base} onChange={e => setM({ ...m, base: e.target.value === '1' ? 1 : 0 })}>
          <option value="0">0-based (children 2i+1, 2i+2)</option>
          <option value="1">1-based (children 2i, 2i+1)</option>
        </select>
      </label>
      <label className="mapping-field">heap size{' '}
        <select aria-label="Heap size" value={m.size ?? ''} onChange={e => setM({ ...m, size: e.target.value || null })}>
          <option value="">whole array</option>
          {vars.ints.map(v => <option key={v} value={v}>{v}</option>)}
        </select>
      </label>
      {m.size && (
        <label className="mapping-field">{m.size} is the{' '}
          <select aria-label="Heap bound" value={m.bound} onChange={e => setM({ ...m, bound: e.target.value === 'last' ? 'last' : 'count' })}>
            <option value="count">number of heap elements</option>
            <option value="last">last heap index</option>
          </select>
        </label>
      )}
      <label className="mapping-field">order{' '}
        <select aria-label="Heap order" value={m.order} onChange={e => setM({ ...m, order: e.target.value === 'min' ? 'min' : 'max' })}>
          <option value="max">max-heap</option>
          <option value="min">min-heap</option>
        </select>
      </label>
      <button className="primary" disabled={problem !== null} onClick={() => onConfirm(m)}>Show as heap</button>
      <button onClick={onCancel}>Cancel</button>
      {problem && <span className="warning">{problem}</span>}
    </div>
  );
}

export function HeapMappings({ trace, mappings, onConfirm, onRemove }: Props) {
  const [editing, setEditing] = useState<string | null>(null); // array name, or '' for a new mapping
  const vars = heapVariables(trace);
  if (vars.arrays.length === 0 && mappings.length === 0) return null;
  const blank: HeapMapping = { array: vars.arrays[0] ?? '', base: 0, size: null, bound: 'count', order: 'max' };
  return (
    <div className="mappings" data-testid="heap-mappings">
      {mappings.map(m => {
        const problem = heapProblem(m, trace);
        return (
          <div className="mapping" key={m.array} data-testid={`heap-mapping-${m.array}`}>
            {editing === m.array
              ? <Editor start={m} trace={trace} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next, m.array); setEditing(null); }} />
              : (
                <>
                  <span>{problem
                    ? <span className="warning">Heap view of {m.array} does not fit this run: {problem}.</span>
                    : <><b>{m.array}</b> shown as a {m.order}-heap ({m.base}-based{m.size ? `, size from ${m.size}` : ', whole array'}).</>}</span>
                  <button onClick={() => setEditing(m.array)}>Change</button>
                  <button onClick={() => onRemove(m.array)}>Remove</button>
                </>
              )}
          </div>
        );
      })}
      {editing === ''
        ? <div className="mapping"><Editor start={blank} trace={trace} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next); setEditing(null); }} /></div>
        : vars.arrays.length > 0 && <div><button onClick={() => setEditing('')}>Show an array as a heap…</button></div>}
    </div>
  );
}
