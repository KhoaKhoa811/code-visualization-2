// Part 14a: matrices the user confirmed as graphs (specs/graph-view.md). Nodes sit in levels by
// distance from the start node; level-tree edges are solid, other edges dashed. A recorded read
// or write of g[i][j] lights edge i→j, and of g[i] lights node i, at that step.
import { formatValue, namesOf } from '../trace/format.ts';
import type { View } from '../trace/playback.ts';
import { projectGraph, type Edge, type GraphMapping, type GraphView } from '../trace/graph.ts';
import { Panel } from './Draggable.tsx';

const DX = 64, DY = 72, R = 16, PAD = 28;

function centre(g: GraphView, u: number): { x: number; y: number } {
  const { level, slot } = g.place[u];
  const count = g.place.filter(p => p.level === level).length;
  const width = g.widest * DX;
  return { x: PAD + (slot + 0.5) * (width / count), y: PAD + level * DY };
}

/** A straight edge between circles, bent when the opposite direction also has an edge. */
function path(g: GraphView, e: Edge, bend: boolean): { d: string; label: { x: number; y: number } } {
  const a = centre(g, e.from), b = centre(g, e.to);
  if (e.from === e.to) { // a self-loop above the node
    return { d: `M ${a.x - 8} ${a.y - R + 2} C ${a.x - 26} ${a.y - 52}, ${a.x + 26} ${a.y - 52}, ${a.x + 8} ${a.y - R + 2}`, label: { x: a.x, y: a.y - 44 } };
  }
  const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy) || 1;
  const ux = dx / len, uy = dy / len;
  const s = { x: a.x + ux * R, y: a.y + uy * R }, t = { x: b.x - ux * (R + 3), y: b.y - uy * (R + 3) };
  if (!bend) return { d: `M ${s.x} ${s.y} L ${t.x} ${t.y}`, label: { x: (s.x + t.x) / 2 - uy * 9, y: (s.y + t.y) / 2 + ux * 9 } };
  const c = { x: (s.x + t.x) / 2 - uy * 18, y: (s.y + t.y) / 2 + ux * 18 };
  return { d: `M ${s.x} ${s.y} Q ${c.x} ${c.y} ${t.x} ${t.y}`, label: { x: c.x - uy * 4, y: c.y + ux * 4 } };
}

function Graph({ view, mapping }: { view: View; mapping: GraphMapping }) {
  const state = view.beforeEnd ?? view.state;
  const projection = projectGraph(state, mapping);
  if (!projection) return <div className="graph-block muted" data-testid={`graph-${mapping.array}`}>{mapping.array} is not in scope at this step.</div>;
  if ('problem' in projection) {
    return <div className="graph-block warning" data-testid={`graph-${mapping.array}`}>The graph view of {mapping.array} does not fit at this step: {projection.problem}.</div>;
  }
  const g = projection.graph;
  const focus = view.focus;
  const row = focus.objectId ? g.rows.indexOf(focus.objectId) : -1;
  const litEdge = row >= 0 && focus.index !== undefined ? [row, focus.index] : null;
  const litNode = focus.objectId === g.matrix.objectId && focus.index !== undefined ? focus.index : null;
  const pairs = new Set(g.edges.map(e => `${e.from}-${e.to}`));
  const width = PAD * 2 + g.widest * DX, height = PAD * 2 + (g.levels - 1) * DY;
  const marker = `arrow-${g.matrix.objectId}`;
  const names = namesOf(state, g.matrix.objectId).filter(n => !n.includes('['));
  return (
    <div className="graph-block" data-testid={`graph-${mapping.array}`} data-graph={g.matrix.objectId}>
      <div className="array-label">
        {names.join(' = ') || mapping.array}{' '}
        <span className="muted">
          {mapping.directed ? 'directed' : 'undirected'} graph, {g.n} node{g.n === 1 ? '' : 's'}, {g.edges.length} edge{g.edges.length === 1 ? '' : 's'}
          {' · '}levels from node {mapping.start}{g.roots.length > 1 ? ` (${g.roots.length - 1} part${g.roots.length > 2 ? 's' : ''} not reached from it, below)` : ''}
        </span>
      </div>
      {!mapping.directed && !g.symmetric && (
        <p className="warning" data-testid="graph-asymmetric">The matrix is not symmetric at this step; differing pairs show both values.</p>
      )}
      <svg className="graph" width={width} height={Math.max(height, PAD * 2)} role="img" aria-label={`graph of ${mapping.array}`}>
        <defs>
          <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="graph-arrowhead" />
          </marker>
        </defs>
        {g.edges.map(e => {
          const bend = mapping.directed && e.from !== e.to && pairs.has(`${e.to}-${e.from}`);
          const { d, label } = path(g, e, bend);
          const lit = litEdge && (litEdge[0] === e.from && litEdge[1] === e.to || !mapping.directed && litEdge[0] === e.to && litEdge[1] === e.from);
          const text = e.weight === null ? null : e.reverse !== undefined && e.reverse !== null
            ? `${formatValue(e.weight, state.objects)}/${formatValue(e.reverse, state.objects)}` : formatValue(e.weight, state.objects);
          return (
            <g key={`${e.from}-${e.to}`} className={`graph-edge${e.tree ? ' tree' : ' extra'}${e.unknown ? ' unknown' : ''}${lit ? ` lit ${focus.access}` : ''}`}
               data-testid="graph-edge" data-from={e.from} data-to={e.to} data-tree={e.tree}>
              <path style={{ d: `path('${d}')` }} d={d} markerEnd={mapping.directed ? `url(#${marker})` : undefined} />
              {(g.weighted || e.unknown) && text !== null && (
                <text x={label.x} y={label.y} className="graph-weight">{e.unknown ? '?' : text}</text>
              )}
            </g>
          );
        })}
        {g.place.map((_, u) => {
          const c = centre(g, u);
          return (
            <g key={u} className={`graph-node${litNode === u ? ` lit ${focus.access}` : ''}${g.roots.includes(u) ? ' root' : ''}`}
               data-testid="graph-node" data-node={u} data-level={g.place[u].level}
               style={{ transform: `translate(${c.x}px, ${c.y}px)` }}>
              <circle r={R} />
              <text className="graph-label" dy="0.35em">{u}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export function GraphPanel({ view, mappings }: { view: View; mappings: GraphMapping[] }) {
  if (mappings.length === 0) return null;
  return (
    <Panel id="graphs" label="Graphs" title="Graphs">
      {mappings.map(m => <Graph key={m.array} view={view} mapping={m} />)}
    </Panel>
  );
}
