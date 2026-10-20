// Frames, bindings and arrays at the playback cursor. Rendering is chosen by
// recorded data shape only (bindings, arrays, arrays of arrays), never by algorithm.
import { arrayType, formatValue, methodName, namesOf, stringText } from '../trace/format.ts';
import type { Focus, State, View } from '../trace/playback.ts';
import type { TraceObject } from '../trace/types.ts';
import { ObjectGraph } from './ObjectGraph.tsx';
import { LibraryPanel } from './LibraryPanel.tsx';

type ArrayObject = TraceObject & { kind: 'array' };

export function StatePanel({ view }: { view: View }) {
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
      <section className="panel" aria-label="Call stack">
        <h2>{state.frames.length > 1 ? `Call stack (${state.frames.length} frames)` : 'Variables'}</h2>
        {view.beforeEnd && <p className="muted" data-testid="ended-note">main has returned; showing the values it ended with.</p>}
        {state.frames.length === 0 && <p className="muted">No active frame.</p>}
        {/* Innermost (running) frame first; each invocation has its own variables, even with the same names. */}
        {[...state.frames].reverse().map((frame, depth) => (
          <div className={`frame${depth === 0 ? ' active' : ''}`} key={frame.frameId} data-testid="frame">
            <div className="frame-name" title={frame.methodId}>
              {methodName(frame.methodId)}{depth === 0 && state.frames.length > 1 && <span className="badge">running</span>}
            </div>
            <table className="bindings">
              <tbody>
                {state.bindings.filter(b => b.frameId === frame.frameId).map(b => (
                  <tr key={b.bindingId} className={focus.bindingId === b.bindingId || focus.touched?.includes(b.bindingId) ? 'changed' : undefined} data-testid={`var-${b.name}`}>
                    <td className="type" title={b.javaType}>{b.javaType.replace(/\b(java\.lang|java\.util)\./g, '').replace(/\bMain\./g, '')}</td>
                    <td className="name">{b.name}</td>
                    <td className="value">{formatValue(b.value, state.objects)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </section>
      <LibraryPanel view={view} />
      <ObjectGraph view={view} />
      <section className="panel" aria-label="Arrays">
        <h2>Arrays</h2>
        {topLevel.length === 0 && <p className="muted">No arrays yet.</p>}
        {topLevel.map(array => {
          const names = namesOf(state, array.objectId);
          const active = focus.objectId === array.objectId;
          return (
            <div className={`array${active && focus.access === 'allocate' || focus.touched?.includes(array.objectId) ? ' allocated' : ''}`} key={array.objectId}
                 data-testid={`array-${names[0] ?? array.objectId}`}>
              <div className="array-label">
                {names.length ? names.join(' = ') : '(temporary)'} <span className="muted">{arrayType(array)}</span>
                {active && focus.access === 'length' && <span className="badge">length</span>}
              </div>
              <ArrayBody array={array} state={state} focus={focus} depth={0} />
            </div>
          );
        })}
      </section>
      <StringsPanel view={view} />
    </div>
  );
}

/** Cells of a one-dimensional array, or one row per element of an array of arrays. */
function ArrayBody({ array, state, focus, depth }: { array: ArrayObject; state: State; focus: Focus; depth: number }) {
  const active = focus.objectId === array.objectId;
  if (!array.elementType.endsWith('[]') || depth > 3) {
    return (
      <div className="cells">
        {array.elements.length === 0 && <span className="muted">empty</span>}
        {array.elements.map((element, index) => {
          const hit = active && focus.index === index ? ` ${focus.access}` : '';
          return (
            <div className={`cell${hit}${element.kind === 'unknown' ? ' unknown' : ''}`} key={index} data-testid="cell">
              <div className="cell-value">{formatValue(element, state.objects)}</div>
              <div className="cell-index">{index}</div>
            </div>
          );
        })}
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

function StringsPanel({ view }: { view: View }) {
  const state = view.beforeEnd ?? view.state;
  const strings = [...state.objects.values()].filter((o): o is TraceObject & { kind: 'string' } => o.kind === 'string')
    .filter(o => namesOf(state, o.objectId).length > 0);
  if (strings.length === 0) return null;
  return (
    <section className="panel" aria-label="Strings">
      <h2>Strings</h2>
      {strings.map(s => (
        <div key={s.objectId} className="string-row">
          <span className="name">{namesOf(state, s.objectId).join(' = ')}</span> {JSON.stringify(stringText(s))}
        </div>
      ))}
    </section>
  );
}
