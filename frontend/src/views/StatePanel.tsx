// Frames, bindings and arrays at the playback cursor. Rendering is chosen by
// recorded data shape only (bindings, arrays), never by algorithm.
import { formatValue, namesOf, stringText } from '../trace/format.ts';
import type { View } from '../trace/playback.ts';
import type { TraceObject } from '../trace/types.ts';

export function StatePanel({ view }: { view: View }) {
  // After main returns, show the recorded values it ended with, labelled as such.
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const arrays = [...state.objects.values()].filter((o): o is TraceObject & { kind: 'array' } => o.kind === 'array');
  return (
    <div className="state">
      <section className="panel" aria-label="Variables">
        <h2>Variables</h2>
        {view.beforeEnd && <p className="muted" data-testid="ended-note">main has returned; showing the values it ended with.</p>}
        {state.frames.length === 0 && <p className="muted">No active frame.</p>}
        {[...state.frames].reverse().map(frame => (
          <div className="frame" key={frame.frameId}>
            <div className="frame-name">{frame.methodId}</div>
            <table className="bindings">
              <tbody>
                {state.bindings.filter(b => b.frameId === frame.frameId).map(b => (
                  <tr key={b.bindingId} className={focus.bindingId === b.bindingId ? 'changed' : undefined} data-testid={`var-${b.name}`}>
                    <td className="type">{b.javaType}</td>
                    <td className="name">{b.name}</td>
                    <td className="value">{formatValue(b.value, state.objects)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </section>
      <section className="panel" aria-label="Arrays">
        <h2>Arrays</h2>
        {arrays.length === 0 && <p className="muted">No arrays yet.</p>}
        {arrays.map(array => {
          const names = namesOf(state, array.objectId);
          const active = focus.objectId === array.objectId;
          return (
            <div className={`array${active && focus.access === 'allocate' ? ' allocated' : ''}`} key={array.objectId}
                 data-testid={`array-${names[0] ?? array.objectId}`}>
              <div className="array-label">
                {names.length ? names.join(' = ') : '(temporary)'} <span className="muted">{array.elementType}[{array.elements.length}]</span>
                {active && focus.access === 'length' && <span className="badge">length</span>}
              </div>
              <div className="cells">
                {array.elements.length === 0 && <span className="muted">empty</span>}
                {array.elements.map((element, index) => {
                  const hit = active && focus.index === index ? ` ${focus.access}` : '';
                  return (
                    <div className={`cell${hit}`} key={index} data-testid="cell">
                      <div className="cell-value">{formatValue(element, state.objects)}</div>
                      <div className="cell-index">{index}</div>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </section>
      <StringsPanel view={view} />
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
