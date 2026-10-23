// Confirming an array or list as a graph (Parts 14a, 14b). Nothing shows one as a graph by
// itself: it is the user's interpretation, checked against each run's recorded variables. The
// editor only hints and suggests from the state at the cursor (fits as a matrix or as lists,
// symmetric); it never guesses for the user.
import { useState } from 'react';
import { graphCandidates, graphProblem, NO_EDGE_CHOICES, projectGraph, type GraphForm, type GraphMapping } from '../trace/graph.ts';
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

const FORM_TEXT: Record<GraphForm, string> = { matrix: 'adjacency matrix (g[i][j] is the edge i → j)', lists: 'neighbour lists (row i lists the neighbours of i)' };

/** A suggestion from the state at the cursor: the form that fits, and undirected while symmetric. */
function suggest(name: string, forms: GraphForm[], state: State | null): GraphMapping {
  const base: GraphMapping = { array: name, form: forms[0], directed: true, noEdge: -1, start: 0 };
  if (!state) return base;
  for (const form of forms) {
    const p = projectGraph(state, { ...base, form });
    if (p && 'graph' in p) return { ...base, form, directed: !p.graph.symmetric };
  }
  return base;
}

function Editor({ start, trace, state, onConfirm, onCancel }: {
  start: GraphMapping; trace: Trace; state: State | null; onConfirm: (m: GraphMapping) => void; onCancel: () => void;
}) {
  const [m, setM] = useState(start);
  const candidates = graphCandidates(trace);
  const forms = candidates.get(m.array) ?? [];
  const form = m.form ?? 'matrix';
  const problem = graphProblem(m, trace);
  const now = state ? projectGraph(state, m) : null;
  const hint = !now ? 'not in scope at this step' : 'problem' in now ? `does not fit at this step: ${now.problem}`
    : `${now.graph.n} nodes, ${now.graph.edges.length} edges; ${now.graph.symmetric ? 'symmetric at this step (undirected fits)' : 'not symmetric at this step (directed fits)'}`;
  return (
    <div className="mapping-editor" data-testid="graph-editor">
      <label className="mapping-field">variable{' '}
        <select aria-label="Graph variable" value={m.array} onChange={e => setM(suggest(e.target.value, candidates.get(e.target.value) ?? [], state))}>
          {[...candidates.keys()].map(a => <option key={a} value={a}>{a}</option>)}
        </select>
      </label>
      <label className="mapping-field">read as{' '}
        <select aria-label="Graph form" value={form} onChange={e => setM({ ...m, form: e.target.value as GraphForm })}>
          {forms.map(f => <option key={f} value={f}>{FORM_TEXT[f]}</option>)}
        </select>
      </label>
      <label className="mapping-field">edges{' '}
        <select aria-label="Graph edges" value={m.directed ? 'directed' : 'undirected'} onChange={e => setM({ ...m, directed: e.target.value === 'directed' })}>
          <option value="directed">directed</option>
          <option value="undirected">undirected</option>
        </select>
      </label>
      {form === 'matrix' && (
        <label className="mapping-field">no edge is{' '}
          <select aria-label="Graph no edge" value={m.noEdge} onChange={e => setM({ ...m, noEdge: Number(e.target.value) })}>
            {NO_EDGE_CHOICES.map(v => <option key={v} value={v}>{v}</option>)}
          </select>
        </label>
      )}
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
  const [editing, setEditing] = useState<string | null>(null); // variable name, or '' for a new mapping
  const candidates = graphCandidates(trace);
  if (candidates.size === 0 && mappings.length === 0) return null;
  const [first, forms] = [...candidates][0] ?? ['', []];
  const blank = suggest(first, forms, state);
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
                    : <><b>{m.array}</b> shown as {m.directed ? 'a directed' : 'an undirected'} graph from {(m.form ?? 'matrix') === 'matrix'
                        ? `a matrix, ${m.noEdge} is no edge` : 'neighbour lists'}; levels from node {m.start}.</>}</span>
                  <button onClick={() => setEditing(m.array)}>Change</button>
                  <button onClick={() => onRemove(m.array)}>Remove</button>
                </>
              )}
          </div>
        );
      })}
      {editing === ''
        ? <div className="mapping"><Editor start={blank} trace={trace} state={state} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next); setEditing(null); }} /></div>
        : candidates.size > 0 && <div><button onClick={() => setEditing('')}>Show a variable as a graph…</button></div>}
    </div>
  );
}
