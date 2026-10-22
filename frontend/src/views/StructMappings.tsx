// Suggesting and confirming list, stack and queue views of user classes (Part 10b). Nothing is drawn
// until confirmed. Each confirmation is a new revision tied to the source it was confirmed for; a
// mapping that no longer fits a run's recorded fields says why and asks for a correction (AC-30).
import { useState } from 'react';
import { shortClass } from '../trace/format.ts';
import { structProblem, suggestStructs, type Confirmed, type StructMapping } from '../trace/structures.ts';
import type { FieldInfo } from '../trace/tree.ts';

interface Props {
  fields: Map<string, FieldInfo[]>;
  sourceSha256: string;
  mappings: Confirmed<StructMapping>[];
  dismissed: string[];
  onConfirm: (mapping: StructMapping) => void;
  onRemove: (className: string) => void;
  onDismiss: (className: string) => void;
}

const INTS = new Set(['int', 'short', 'byte', 'long']);

function describe(m: StructMapping): string {
  if (m.kind === 'list') return `a linked list: value ${m.value}, next ${m.next}`;
  if (m.kind === 'stack') return `a stack: storage ${m.storage}, ${m.top} as ${m.topIs === 'count' ? 'size' : 'top index'}`;
  return `a queue: storage ${m.storage}, front ${m.front}, rear ${m.rear} (${m.rearIs === 'next' ? 'next free slot' : 'last element'})${m.size ? `, size ${m.size}` : ''}`;
}

/** The same class seen as the other array-backed kind, with sensible field choices. */
function asKind(m: StructMapping, kind: 'stack' | 'queue', fields: FieldInfo[]): StructMapping {
  const storage = m.kind === 'list' ? (fields.find(f => f.javaType.endsWith('[]'))?.name ?? '') : m.storage;
  const ints = fields.filter(f => INTS.has(f.javaType)).map(f => f.name);
  if (kind === 'stack') return { kind, className: m.className, storage, top: ints[0] ?? '', topIs: 'count' };
  return { kind, className: m.className, storage, front: ints[0] ?? '', rear: ints[1] ?? ints[0] ?? '', rearIs: 'next', size: ints[2] ?? null };
}

function Editor({ start, fields, onConfirm, onCancel }: { start: StructMapping; fields: FieldInfo[]; onConfirm: (m: StructMapping) => void; onCancel: () => void }) {
  const [m, setM] = useState<StructMapping>(start);
  const problem = structProblem(m, new Map([[m.className, fields]]));
  const name = shortClass(m.className);
  const pick = (label: string, value: string, options: string[], change: (v: string) => void, allowNone = false) => (
    <label className="mapping-field">{label}{' '}
      <select aria-label={`${name} ${label}`} value={value} onChange={e => change(e.target.value)}>
        {allowNone && <option value="">none</option>}
        {options.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    </label>
  );
  const all = fields.map(f => f.name);
  const links = fields.filter(f => f.javaType === m.className).map(f => f.name);
  const arrays = fields.filter(f => f.javaType.endsWith('[]')).map(f => f.name);
  const ints = fields.filter(f => INTS.has(f.javaType)).map(f => f.name);
  return (
    <div className="mapping-editor" data-testid={`struct-editor-${name}`}>
      {m.kind !== 'list' && pick('kind', m.kind, ['stack', 'queue'], k => setM(asKind(m, k as 'stack' | 'queue', fields)))}
      {m.kind === 'list' && <>{pick('value', m.value, all, v => setM({ ...m, value: v }))}{pick('next', m.next, links, v => setM({ ...m, next: v }))}</>}
      {m.kind === 'stack' && <>
        {pick('storage', m.storage, arrays, v => setM({ ...m, storage: v }))}
        {pick('top', m.top, ints, v => setM({ ...m, top: v }))}
        {pick('top is', m.topIs, ['count', 'index'], v => setM({ ...m, topIs: v === 'index' ? 'index' : 'count' }))}
      </>}
      {m.kind === 'queue' && <>
        {pick('storage', m.storage, arrays, v => setM({ ...m, storage: v }))}
        {pick('front', m.front, ints, v => setM({ ...m, front: v }))}
        {pick('rear', m.rear, ints, v => setM({ ...m, rear: v }))}
        {pick('rear is', m.rearIs, ['next', 'last'], v => setM({ ...m, rearIs: v === 'last' ? 'last' : 'next' }))}
        {pick('size', m.size ?? '', ints, v => setM({ ...m, size: v || null }), true)}
      </>}
      <button className="primary" disabled={problem !== null} onClick={() => onConfirm(m)}>Confirm</button>
      <button onClick={onCancel}>Cancel</button>
      {problem && <span className="warning">{problem}</span>}
    </div>
  );
}

export function StructMappings({ fields, sourceSha256, mappings, dismissed, onConfirm, onRemove, onDismiss }: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const confirmed = new Set(mappings.map(m => m.className));
  const suggestions = suggestStructs(fields).filter(s => !confirmed.has(s.className) && !dismissed.includes(s.className));
  if (suggestions.length === 0 && mappings.length === 0) return null;
  return (
    <div className="mappings" data-testid="struct-mappings">
      {mappings.map(m => {
        const problem = structProblem(m, fields);
        const name = shortClass(m.className);
        const list = fields.get(m.className);
        return (
          <div className="mapping" key={m.className} data-testid={`struct-mapping-${name}`}>
            {editing === m.className && list
              ? <Editor start={m} fields={list} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next); setEditing(null); }} />
              : (
                <>
                  <span>{problem
                    ? <span className="warning">{name} view (revision {m.revision}) does not fit this run: {problem}. Choose the fields again or remove it.</span>
                    : <><b>{name}</b> shown as {describe(m)} <span className="muted">(revision {m.revision}, confirmed for {m.confirmedFor === sourceSha256 ? 'this source' : 'an earlier source; it still fits'})</span>.</>}</span>
                  {list && <button onClick={() => setEditing(m.className)}>Change</button>}
                  <button onClick={() => onRemove(m.className)}>Remove</button>
                </>
              )}
          </div>
        );
      })}
      {suggestions.map(s => {
        const name = shortClass(s.className);
        const list = fields.get(s.className)!;
        return (
          <div className="mapping suggestion" key={s.className} data-testid={`struct-suggestion-${name}`}>
            {editing === s.className
              ? <Editor start={s} fields={list} onCancel={() => setEditing(null)} onConfirm={next => { onConfirm(next); setEditing(null); }} />
              : s.kind === 'list'
                ? (
                  <>
                    <span><b>{name}</b> has one {name} field: it may be a linked list node (value {s.value}, next {s.next}).</span>
                    <button className="primary" onClick={() => onConfirm(s)}>Show as list</button>
                    <button onClick={() => setEditing(s.className)}>Choose fields</button>
                    <button onClick={() => onDismiss(s.className)}>Not a list</button>
                  </>
                )
                : (
                  <>
                    <span><b>{name}</b> has an array and int fields: it may be a stack or a queue built on an array.</span>
                    <button className="primary" onClick={() => onConfirm(asKind(s, 'stack', list))}>Show as stack</button>
                    <button className="primary" onClick={() => onConfirm(asKind(s, 'queue', list))}>Show as queue</button>
                    <button onClick={() => setEditing(s.className)}>Choose fields</button>
                    <button onClick={() => onDismiss(s.className)}>Neither</button>
                  </>
                )}
          </div>
        );
      })}
    </div>
  );
}
