// Suggesting and confirming tree views (Part 9b). A suggestion only proposes a presentation;
// nothing is drawn as a tree until the user confirms which fields are the value and the
// children. Confirmed mappings are checked again against every run (AC-30 starts here).
import { useState } from 'react';
import { shortClass } from '../trace/format.ts';
import { mappingProblem, suggestTrees, type FieldInfo, type TreeMapping } from '../trace/tree.ts';

interface Props {
  fields: Map<string, FieldInfo[]>;
  mappings: TreeMapping[];
  dismissed: string[];
  onConfirm: (mapping: TreeMapping) => void;
  onRemove: (className: string) => void;
  onDismiss: (className: string) => void;
}

function Editor({ start, fields, onConfirm, onCancel, confirmLabel }: {
  start: TreeMapping; fields: FieldInfo[]; onConfirm: (m: TreeMapping) => void; onCancel: () => void; confirmLabel: string;
}) {
  const [m, setM] = useState(start);
  const problem = mappingProblem(m, new Map([[m.className, fields]]));
  const select = (label: string, key: 'value' | 'left' | 'right', options: FieldInfo[]) => (
    <label className="mapping-field">{label}{' '}
      <select aria-label={`${shortClass(m.className)} ${label}`} value={m[key]} onChange={e => setM({ ...m, [key]: e.target.value })}>
        {options.map(f => <option key={f.name} value={f.name}>{f.name}</option>)}
      </select>
    </label>
  );
  const links = fields.filter(f => f.javaType === m.className);
  return (
    <div className="mapping-editor">
      {select('value', 'value', fields)}
      {select('left', 'left', links)}
      {select('right', 'right', links)}
      <button className="primary" disabled={problem !== null} onClick={() => onConfirm(m)}>{confirmLabel}</button>
      <button onClick={onCancel}>Cancel</button>
      {problem && <span className="warning">{problem}</span>}
    </div>
  );
}

export function TreeMappings({ fields, mappings, dismissed, onConfirm, onRemove, onDismiss }: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const confirmed = new Set(mappings.map(m => m.className));
  const suggestions = suggestTrees(fields).filter(s => !confirmed.has(s.className) && !dismissed.includes(s.className));
  if (suggestions.length === 0 && mappings.length === 0) return null;
  return (
    <div className="mappings" data-testid="tree-mappings">
      {mappings.map(m => {
        const problem = mappingProblem(m, fields);
        const name = shortClass(m.className);
        return (
          <div className="mapping" key={m.className} data-testid={`mapping-${name}`}>
            {editing === m.className && fields.has(m.className)
              ? <Editor start={m} fields={fields.get(m.className)!} confirmLabel="Save" onCancel={() => setEditing(null)}
                        onConfirm={next => { onConfirm(next); setEditing(null); }} />
              : (
                <>
                  <span>{problem
                    ? <span className="warning">{name} tree view does not fit this run: {problem}.</span>
                    : <><b>{name}</b> shown as a tree: value {m.value}, children {m.left} / {m.right}.</>}</span>
                  {fields.has(m.className) && <button onClick={() => setEditing(m.className)}>Change</button>}
                  <button onClick={() => onRemove(m.className)}>Remove</button>
                </>
              )}
          </div>
        );
      })}
      {suggestions.map(s => {
        const name = shortClass(s.className);
        return (
          <div className="mapping suggestion" key={s.className} data-testid={`suggestion-${name}`}>
            {editing === s.className
              ? <Editor start={s} fields={fields.get(s.className)!} confirmLabel="Show as tree" onCancel={() => setEditing(null)}
                        onConfirm={next => { onConfirm(next); setEditing(null); }} />
              : (
                <>
                  <span><b>{name}</b> has two {name} fields: it may be a binary tree node (value {s.value}, children {s.left} / {s.right}).</span>
                  <button className="primary" onClick={() => onConfirm(s)}>Show as tree</button>
                  <button onClick={() => setEditing(s.className)}>Choose fields</button>
                  <button onClick={() => onDismiss(s.className)}>Not a tree</button>
                </>
              )}
          </div>
        );
      })}
    </div>
  );
}
