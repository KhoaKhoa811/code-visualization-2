// Library collections, maps and string builders (Part 8f), drawn by their class:
// lists as indexed cells, stacks vertically with the top first, deques and queues
// with first/last ends, sets as unordered chips, priority queues in poll order with
// the head marked, maps as key/value rows. The view comes from the recorded class
// only; an ArrayDeque or LinkedList is shown as a stack or a queue only when the user
// chooses that view (Part 9d): push, pop and peek work on the first element (the top),
// a queue polls the first element (the front) and offers at the last (the back).
import { formatValue, namesOf, shortName } from '../trace/format.ts';
import type { Focus, State, View } from '../trace/playback.ts';
import type { TraceObject, Value } from '../trace/types.ts';
import { Draggable, Panel, useLayout } from './Draggable.tsx';
import { setMode, type CollectionMode } from './layout.ts';

type LibraryObject = TraceObject & { kind: 'collection' | 'map' | 'builder' };

function cell(value: Value, state: State, key: string | number, extra = '') {
  return (
    <div className={`cell${value.kind === 'unknown' ? ' unknown' : ''}${extra}`} key={key} data-testid="item">
      <div className="cell-value">{formatValue(value, state.objects)}</div>
    </div>
  );
}

function Body({ object, state, mode }: { object: LibraryObject; state: State; mode?: CollectionMode }) {
  const name = shortName(object.className);
  if (object.kind === 'builder') {
    const text = String.fromCharCode(...object.utf16);
    return (
      <div className="cells">
        {text.length === 0 && <span className="muted">empty</span>}
        {[...text].map((ch, i) => (
          <div className="cell" key={i} data-testid="item">
            <div className="cell-value">{ch}</div>
            <div className="cell-index">{i}</div>
          </div>
        ))}
      </div>
    );
  }
  if (object.kind === 'map') {
    return (
      <table className="map-table">
        <tbody>
          {object.entries.length === 0 && <tr><td className="muted">empty</td></tr>}
          {object.entries.map((e, i) => (
            <tr key={i} data-testid="entry">
              <td className="map-key">{formatValue(e.key, state.objects)}</td>
              <td className="muted">→</td>
              <td className={e.value.kind === 'unknown' ? 'unknown' : undefined}>{formatValue(e.value, state.objects)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  const elements = object.elements;
  if (elements.length === 0) return <div className="cells"><span className="muted">{mode ? `empty ${mode}` : 'empty'}</span></div>;
  if (mode === 'stack') { // the first element is the top: vertical, top first
    return (
      <div className="stack-cells" data-testid="as-stack">
        {elements.map((v, i) => cell(v, state, i, i === 0 ? ' top' : ''))}
      </div>
    );
  }
  if (mode === 'queue') { // front (first) to back (last)
    return (
      <div className="cells" data-testid="as-queue">
        {elements.map((v, i) => (
          <div className={`cell${v.kind === 'unknown' ? ' unknown' : ''}${i === 0 ? ' top' : ''}`} key={i} data-testid="item">
            <div className="cell-value">{formatValue(v, state.objects)}</div>
            <div className="cell-index">{[i === 0 && 'front', i === elements.length - 1 && 'back'].filter(Boolean).join(' / ')}</div>
          </div>
        ))}
      </div>
    );
  }
  if (name === 'Stack') { // vertical, top first
    return (
      <div className="stack-cells">
        {[...elements].reverse().map((v, i) => cell(v, state, i, i === 0 ? ' top' : ''))}
      </div>
    );
  }
  if (name === 'PriorityQueue') {
    return <div className="cells">{elements.map((v, i) => cell(v, state, i, i === 0 ? ' top' : ''))}</div>;
  }
  if (name.endsWith('Set')) {
    return <div className="cells set-cells">{elements.map((v, i) => cell(v, state, i))}</div>;
  }
  const deque = name === 'ArrayDeque';
  return (
    <div className="cells">
      {elements.map((v, i) => (
        <div className={`cell${v.kind === 'unknown' ? ' unknown' : ''}`} key={i} data-testid="item">
          <div className="cell-value">{formatValue(v, state.objects)}</div>
          <div className="cell-index">{deque ? (i === 0 ? 'first' : i === elements.length - 1 ? 'last' : '') : i}</div>
        </div>
      ))}
    </div>
  );
}

const NOTES: Record<string, string> = {
  Stack: 'top first',
  PriorityQueue: 'poll order; head first',
  HashSet: 'no defined order (shown ascending)',
  HashMap: 'no defined order (shown by key)',
  TreeSet: 'sorted',
  TreeMap: 'sorted by key',
};

/** Classes with both stack and queue operations: the user may choose either view (Part 9d). */
const SWITCHABLE = new Set(['ArrayDeque', 'LinkedList']);

function ModeSwitch({ name, variable, mode }: { name: string; variable: string; mode?: CollectionMode }) {
  const { update } = useLayout();
  const options: [CollectionMode | null, string][] = [[null, name === 'LinkedList' ? 'List' : 'Deque'], ['stack', 'Stack'], ['queue', 'Queue']];
  return (
    <span className="mode-switch" role="group" aria-label={`${variable} view`}>
      {options.map(([value, label]) => (
        <button key={label} aria-pressed={(mode ?? null) === value} className={(mode ?? null) === value ? 'selected' : undefined}
                onClick={() => update(l => setMode(l, variable, value))}>{label}</button>
      ))}
    </span>
  );
}

export function LibraryPanel({ view }: { view: View }) {
  const { layout } = useLayout();
  const state = view.beforeEnd ?? view.state;
  const focus: Focus = view.focus;
  const objects = [...state.objects.values()].filter((o): o is LibraryObject =>
    o.kind === 'collection' || o.kind === 'map' || o.kind === 'builder');
  if (objects.length === 0) return null;
  return (
    <Panel id="collections" label="Collections" title="Collections">
      {objects.map(o => {
        const names = namesOf(state, o.objectId);
        const name = shortName(o.className);
        // A view choice belongs to a variable, so it applies again in later steps and runs.
        const switchable = SWITCHABLE.has(name) && o.kind === 'collection' && names.length > 0;
        const mode = switchable ? layout.modes[names[0]] : undefined;
        const active = focus.objectId === o.objectId || focus.touched?.includes(o.objectId);
        return (
          <Draggable id={`collection:${o.objectId}`} className={`library${active ? ` ${focus.access === 'write' || focus.touched ? 'changed' : 'read'}` : ''}`} key={o.objectId}
               data-testid={`library-${names[0] ?? o.objectId}`}>
            <div className="array-label">
              {names.length ? names.join(' = ') : '(temporary)'} <span className="muted">{name}</span>
              {mode ? <span className="muted"> · {mode === 'stack' ? 'top = first element' : 'front = first, back = last'}</span>
                : NOTES[name] && <span className="muted"> · {NOTES[name]}</span>}
              {switchable && <ModeSwitch name={name} variable={names[0]} mode={mode} />}
            </div>
            <Body object={o} state={state} mode={mode} />
          </Draggable>
        );
      })}
    </Panel>
  );
}
