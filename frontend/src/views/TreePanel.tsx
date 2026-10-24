// Confirmed binary-tree views of user objects (Part 9b). Nodes sit at their in-order position
// and depth; edges are the recorded left/right references. A node reached twice or through a
// cycle is drawn once and the extra link is dashed and reported, never repaired (AC-34).
import { useLayoutEffect, useRef, useState } from 'react';
import { formatValue, shortClass } from '../trace/format.ts';
import { VarTags } from './VarTags.tsx';
import type { View } from '../trace/playback.ts';
import { positions, projectTree, type Child, type TreeMapping, type TreeNode } from '../trace/tree.ts';
import { Draggable, Panel } from './Draggable.tsx';

const DX = 58, DY = 66;

interface Link { from: string; to: string; repeat: boolean }
interface Line { key: string; x1: number; y1: number; x2: number; y2: number; repeat: boolean }

function Tree({ view, mapping }: { view: View; mapping: TreeMapping }) {
  const state = view.beforeEnd ?? view.state;
  const focus = view.focus;
  const rootOrder = state.bindings.flatMap(b => (b.value.kind === 'ref' ? [b.value.objectId] : []));
  const projection = projectTree(state, mapping, rootOrder);
  const pos = positions(projection);
  const container = useRef<HTMLDivElement>(null);
  const [lines, setLines] = useState<Line[]>([]);

  const nodes: TreeNode[] = [];
  const links: Link[] = [];
  const walk = (n: TreeNode) => {
    nodes.push(n);
    for (const c of [n.left, n.right]) {
      if (c.kind === 'node') { links.push({ from: n.objectId, to: c.node.objectId, repeat: false }); walk(c.node); }
      if (c.kind === 'repeat') links.push({ from: n.objectId, to: c.objectId, repeat: true });
    }
  };
  projection.roots.forEach(walk);

  useLayoutEffect(() => {
    const root = container.current;
    if (!root) return;
    const base = root.getBoundingClientRect();
    const box = (id: string) => root.querySelector<HTMLElement>(`[data-tree-node="${id}"]`)?.getBoundingClientRect();
    const next: Line[] = [];
    for (const l of links) {
      const a = box(l.from), b = box(l.to);
      if (!a || !b) continue;
      next.push({ key: `${l.from}-${l.to}-${l.repeat}`, repeat: l.repeat,
        x1: a.left + a.width / 2 - base.left, y1: a.bottom - base.top, x2: b.left + b.width / 2 - base.left, y2: b.top - base.top });
    }
    setLines(prev => JSON.stringify(prev) === JSON.stringify(next) ? prev : next);
  });

  if (nodes.length === 0) return null;
  const width = Math.max(...[...pos.values()].map(p => p.x)) * DX + DX + 20;
  const height = Math.max(...nodes.map(n => n.depth)) * DY + DY;
  const marker = (c: Child, side: string) =>
    c.kind === 'unknown' ? <span className="tree-stub unknown" title={`${side} unknown`}>{side} ?</span>
      : c.kind === 'other' ? <span className="tree-stub" title={`${side} is not a ${shortClass(mapping.className)}`}>{side} {formatValue(c.value, state.objects)}</span>
        : null;
  return (
    <div className="tree-block" data-testid={`tree-${shortClass(mapping.className)}`}>
      <div className="array-label">
        {shortClass(mapping.className)} <span className="muted">value {mapping.value} · children {mapping.left} / {mapping.right}</span>
      </div>
      {projection.conflicts.length > 0 && (
        <p className="warning" data-testid="tree-conflicts">
          Not a tree here: {projection.conflicts.join(', ')} (each node is drawn once; the extra link is dashed).
        </p>
      )}
      <div className="tree" ref={container} style={{ width, height }}>
        <svg className="tree-edges" aria-hidden="true">
          {lines.map(l => <line key={l.key} x1={l.x1} y1={l.y1} x2={l.x2} y2={l.y2} className={l.repeat ? 'repeat' : undefined} />)}
        </svg>
        {nodes.map(n => {
          const p = pos.get(n.objectId)!;
          const hit = focus.objectId === n.objectId ? ` ${focus.access ?? ''}` : '';
          return (
            <Draggable id={`tree:${n.objectId}`} key={n.objectId} className={`tree-node${hit}${n.value.kind === 'unknown' ? ' unknown' : ''}`}
                       data-tree-node={n.objectId} data-testid="tree-node" title={n.objectId}
                       style={{ left: p.x * DX, top: p.y * DY }}>
              <span className="tree-value">{formatValue(n.value, state.objects)}</span>
              {hit && focus.field && <span className="tree-field">.{focus.field}</span>}
              <VarTags view={view} objectId={n.objectId} />
              {marker(n.left, 'L')}{marker(n.right, 'R')}
            </Draggable>
          );
        })}
      </div>
    </div>
  );
}

export function TreePanel({ view, mappings }: { view: View; mappings: TreeMapping[] }) {
  const state = view.beforeEnd ?? view.state;
  const present = mappings.filter(m => [...state.objects.values()].some(o => o.kind === 'object' && o.className === m.className));
  if (present.length === 0) return null;
  return (
    <Panel id="trees" label="Trees" title="Trees">
      {present.map(m => <Tree key={m.className} view={view} mapping={m} />)}
    </Panel>
  );
}
