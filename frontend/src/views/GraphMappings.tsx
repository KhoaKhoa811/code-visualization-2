// Confirming a two-dimensional array as a graph (Part 14a). Nothing shows a matrix as a graph by
// itself: it is the user's interpretation, checked against each run's recorded variables. The
// editor only hints from the state at the cursor (symmetric, number of rows); it never guesses.
import { useState } from 'react';
import { graphProblem, graphVariables, NO_EDGE_CHOICES, projectGraph, type GraphMapping } from '../trace/graph.ts';
import type { State } from '../trace/playback.ts';
import type { Trace } from '../trace/types.ts';

interface Props {
  trace: Trace;
  /** The state at the cursor, for hints only. */
  state: State | null;
  mappings: GraphMapping[];
  onConfirm: (mapping: GraphMapping, replacing?: string) => void;
  onRemove: (array: string) => void;
}

function Editor({ start, trace, state, onConfirm, onCancel }: {
  start: GraphMapping; trace: Trace; state: State | null; onConfirm: (m: GraphMapping) => void; onCancel: () => void;
}) {
  const [m, setM] = useState(start);
  const arrays = graphVariables(trace);
  const problem = graphProblem(m, trace);
  const now = state ? projectGraph(state, m) : null;
  const hint = !now ? 'not in scope at this step' : 'problem' in now ? now.problem
    : `${now.graph.n} nodes; ${now.graph.symmetric ? 'symmetric at this step (undirected fits)' : 'not symmetric at this step (directed fits)'}`;
  return (
    <div className="mapping-editor" data-testid="graph-editor">
      <label className="mapping-field">matrix{' '}
        <select aria-label="Graph matrix" value={m.array} onChange={e => setM({ ...m, array: e.target.value })}>
          {arrays.map(a => <option key={a} value={a}>{a}</option>)}
        </select>
      </label>
      <label className="mapping-field">edges{' '}
        <select aria-label="Graph edges" value={m.directed ? 'directed' : 'undirected'} onChange={e => setM({ ...m, directed: e.target.value === 'directed' })}>
          <option value="directed">directed (g[i][j] is i → j)</option>
          <option value="undirected">undirected (g[i][j] joins i and j)</option>
        </select>
      </label>
      <label className="mapping-field">no edge is{' '}
        <select aria-label="Graph no edge" value={m.noEdge} onChange={e => setM({ ...m, noEdge: Number(e.target.value) })}>
          {NO_EDGE_CHOICES.map(v => <option key={v} value={v}>{v}</option>)}
        </select>
      </label>
      <label className="mapping-field">levels from node{' '}
        <input aria-label="Graph start node" type="number" min={0} value={m.start} style={{ width: '4em' }}
               onChange={e => setM({ ...m, start: Math.trunc(Number(e.target.value)) })} />
      </label>
      <button className="primary" disabled={problem !== null} onClick={() => onConfirm(m)}>Show as graph</button>
      <button onClick={onCancel}>Cancel</button>
      {problem ? <span className="warning">{problem}</span> : <span className="muted" data-testid="graph-hint">{hint}</span>}
    </div>
  );
}

export function GraphMappings({ trace, state, mappings, onConfirm, onRemove }: Props) {
  const [editing, setEditing] = useState<string | null>(null); // array name, or '' for a new mapping
  const arrays = graphVariables(trace);
  if (arrays.length === 0 && mappings.length === 0) return null;
  const first = arrays[0] ?? '';
  const now = state && first ? projectGraph(state, { array: first, directed: true, noEdge: -1, start: 0 }) : null;
  // Suggest undirected while the matrix is symmetric at this step; the user decides.
  const blank: GraphMapping = { array: first, directed: !(now && 'graph' in now && now.graph.symmetric), noEdge: -1, start: 0 };
  return (
    <div className="mappings" data-testid="graph-mappings">
      {mappings.map(m => {
        const problem = graphProblem(m, trace);
        return (
          <div className="mapping" key={m.array} data-testid={`graph-mapping-${m.array}`}>
            {editing === m.array
              ? <Editor start={m} trace={trace} state={state} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next, m.array); setEditing(null); }} />
              : (
                <>
                  <span>{problem
                    ? <span className="warning">Graph view of {m.array} does not fit this run: {problem}.</span>
                    : <><b>{m.array}</b> shown as {m.directed ? 'a directed' : 'an undirected'} graph ({m.noEdge} is no edge, levels from node {m.start}).</>}</span>
                  <button onClick={() => setEditing(m.array)}>Change</button>
                  <button onClick={() => onRemove(m.array)}>Remove</button>
                </>
              )}
          </div>
        );
      })}
      {editing === ''
        ? <div className="mapping"><Editor start={blank} trace={trace} state={state} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next); setEditing(null); }} /></div>
        : arrays.length > 0 && <div><button onClick={() => setEditing('')}>Show a matrix as a graph…</button></div>}
    </div>
  );
}
