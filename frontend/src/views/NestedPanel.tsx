// Part 19d: structures whose parts hold several others: trees whose children are kept in an
// array, list or map (n-ary trees, tries), and arrays whose slots start linked chains (hash
// tables with chaining). Drawn from the recorded state at the cursor; a node reached twice is
// drawn once and reported. Variables and other structures pointing at a node are tags on it.
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, shortClass } from '../trace/format.ts';
import type { View } from '../trace/playback.ts';
import { childPositions, projectChildren, type ChildrenMapping } from '../trace/children.ts';
import { projectChains, type ChainMapping } from '../trace/chains.ts';
import { Draggable, Panel } from './Draggable.tsx';
import { VarTags } from './VarTags.tsx';

const DX = 64, DY = 70;

function ChildrenTree({ view, mapping }: { view: View; mapping: ChildrenMapping }) {
  const state = view.beforeEnd ?? view.state;
  const roots = state.bindings.flatMap(b => (b.value.kind === 'ref' ? [b.value.objectId] : []));
  const p = projectChildren(state, mapping, roots);
  const pos = childPositions(p);
  const box = useRef<HTMLDivElement>(null);
  const [lines, setLines] = useState<{ key: string; x1: number; y1: number; x2: number; y2: number; label: string }[]>([]);
  const edges: { from: string; to: string; label: string }[] = [];
  const walk = (n: (typeof p.roots)[number]) => n.kids.forEach(k => { if (k.node) { edges.push({ from: n.objectId, to: k.node.objectId, label: k.label }); walk(k.node); } });
  p.roots.forEach(walk);
  useLayoutEffect(() => {
    const root = box.current;
    if (!root) return;
    const base = root.getBoundingClientRect();
    const at = (id: string) => root.querySelector<HTMLElement>(`[data-child-node="${id}"]`)?.getBoundingClientRect();
    const next = edges.flatMap(e => {
      const a = at(e.from), b = at(e.to);
      return a && b ? [{ key: `${e.from}-${e.to}`, label: e.label, x1: a.left + a.width / 2 - base.left, y1: a.bottom - base.top,
        x2: b.left + b.width / 2 - base.left, y2: b.top - base.top }] : [];
    });
    setLines(prev => JSON.stringify(prev) === JSON.stringify(next) ? prev : next);
  });
  if (p.nodes === 0) return null;
  const width = (Math.max(0, ...[...pos.values()].map(q => q.x)) + 1) * DX + 20;
  const height = (Math.max(0, ...[...pos.values()].map(q => q.y)) + 1) * DY;
  const focus = view.focus;
  const nodes: { id: string; value: (typeof p.roots)[number]['value'] }[] = [];
  const collect = (n: (typeof p.roots)[number]) => { nodes.push({ id: n.objectId, value: n.value }); n.kids.forEach(k => k.node && collect(k.node)); };
  p.roots.forEach(collect);
  return (
    <div className="struct-block" data-testid={`children-${shortClass(mapping.className)}`}>
      <div className="array-label">{shortClass(mapping.className)} <span className="muted">
        {mapping.keyed ? 'children by key' : 'children in order'} in {mapping.children}{mapping.value ? ` · value ${mapping.value}` : ''}</span></div>
      {p.conflicts.length > 0 && <p className="warning">Not a tree here: {p.conflicts.join(', ')} (drawn once, as recorded).</p>}
      <div className="tree" ref={box} style={{ width, height }}>
        <svg className="tree-edges" aria-hidden="true">
          {lines.map(l => (
            <g key={l.key}>
              <line x1={l.x1} y1={l.y1} x2={l.x2} y2={l.y2} />
              <text className="edge-label" x={(l.x1 + l.x2) / 2 + 4} y={(l.y1 + l.y2) / 2} data-testid="child-edge-label">{l.label}</text>
            </g>
          ))}
        </svg>
        {nodes.map(n => {
          const q = pos.get(n.id)!;
          const hit = focus.objectId === n.id ? ` ${focus.access ?? ''}` : '';
          return (
            <Draggable id={`child:${n.id}`} key={n.id} className={`tree-node${hit}`} data-child-node={n.id} data-testid="child-node"
                       style={{ left: q.x * DX, top: q.y * DY }} title={n.id}>
              <span className="tree-value">{n.value ? formatValue(n.value, state.objects) : '•'}</span>
              <VarTags view={view} objectId={n.id} />
            </Draggable>
          );
        })}
      </div>
    </div>
  );
}

function Chains({ view, mapping }: { view: View; mapping: ChainMapping }) {
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  return (
    <>
      {projectChains(state, mapping).map(t => (
        <div className="struct-block" key={t.arrayId} data-testid={`chains-${mapping.array}`}>
          <div className="array-label">{mapping.owner ? `${shortClass(mapping.owner)}.` : ''}{mapping.array} <span className="muted">slots start chains of {shortClass(mapping.nodeClass)} through {mapping.next}</span></div>
          {t.conflicts.length > 0 && <p className="warning">{t.conflicts.join(', ')} (drawn as recorded).</p>}
          <div className="chain-slots">
            {t.slots.map(s => (
              <div className={`chain-slot${focus.objectId === t.arrayId && focus.index === s.index ? ` ${focus.access}` : ''}`} key={s.index} data-testid="chain-slot"
                   data-object={t.arrayId} data-index={s.index}>
                <span className="chain-index">{s.index}</span>
                {s.nodes.length === 0 && <span className="muted cell-value">∅</span>}
                {s.nodes.map((n, i) => (
                  <span key={n.objectId} className="chain-link">
                    {i > 0 && <span className="chain-arrow">→</span>}
                    <span className={`chain-node${focus.objectId === n.objectId ? ` ${focus.access ?? ''}` : ''}`} data-testid="chain-node" title={n.objectId}>
                      {n.shown.map((v, k) => <span key={k} className={k === 0 ? 'chain-key' : 'chain-value'}>{formatValue(v, state.objects)}</span>)}
                      <VarTags view={view} objectId={n.objectId} held={false} />
                    </span>
                  </span>
                ))}
                {s.end === 'repeat' && <span className="warning"> ↺</span>}
              </div>
            ))}
          </div>
        </div>
      ))}
    </>
  );
}

export function NestedPanel({ view, trees, chains }: { view: View; trees: ChildrenMapping[]; chains: ChainMapping[] }) {
  if (trees.length === 0 && chains.length === 0) return null;
  return (
    <Panel id="nested" label="Trees and tables" title="Trees and tables" testId="nested">
      {trees.map(m => <ChildrenTree key={m.className} view={view} mapping={m} />)}
      {chains.map(m => <Chains key={`${m.owner}.${m.array}`} view={view} mapping={m} />)}
    </Panel>
  );
}
