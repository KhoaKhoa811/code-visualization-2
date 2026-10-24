// Part 22: the step's actions over every panel (specs/step-actions.md): value arrows (Part 21),
// swaps, rewired links, a reference variable moving between nodes, a comparison bracket, a returned
// value, a push or pop, a hash slot, and the replaced value crossed out. Placed from the measured
// elements after layout; an action whose ends are not drawn is left out. The set is chosen and
// limited by actionsAt, so one picture never carries more than a few at once.
import { useLayoutEffect, useRef, useState } from 'react';
import type { Action } from '../trace/actions.ts';
import { elementOf, type Anchor } from './motion.ts';

type Tone = 'value' | 'swap' | 'old' | 'new' | 'move' | 'yes' | 'no' | 'return' | 'stack' | 'slot';

interface Shape {
  d: string;
  tone: Tone;
  heads: 'end' | 'both' | 'none';
  label?: { x: number; y: number; text: string };
  cross?: { x: number; y: number };
}

interface Ghost { x: number; y: number; text: string }

export function ActionOverlay({ actions }: { actions: Action[] }) {
  const svg = useRef<SVGSVGElement>(null);
  const [drawn, setDrawn] = useState<{ shapes: Shape[]; ghosts: Ghost[] }>({ shapes: [], ghosts: [] });
  useLayoutEffect(() => {
    const host = svg.current?.parentElement;
    if (!host) return;
    const base = host.getBoundingClientRect();
    const rect = (a: Anchor) => {
      let el = elementOf(a);
      // A cell drawn without its own value box (a slot of a chained table) is the whole element.
      if (!el && 'cell' in a) el = document.querySelector(`[data-object="${CSS.escape(a.cell.objectId)}"][data-index="${a.cell.index}"]`);
      if (!el || !host.contains(el)) return null;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 ? r : null;
    };
    const shapes: Shape[] = [], ghosts: Ghost[] = [];
    /** A curved arrow between two drawn places; same-row places get an arc over their tops. */
    const link = (from: Anchor, to: Anchor, tone: Tone, heads: Shape['heads'], text?: string, cross = false) => {
      const p = rect(from), q = rect(to);
      if (!p || !q) return;
      const x1 = p.left + p.width / 2 - base.left, y1 = p.top + p.height / 2 - base.top;
      const x2 = q.left + q.width / 2 - base.left, y2 = q.top + q.height / 2 - base.top;
      if (Math.abs(y2 - y1) < 8) {
        const top = Math.min(p.top, q.top) - base.top, h = Math.max(heads === 'none' ? 18 : 30, Math.abs(x2 - x1) * 0.3);
        shapes.push({ d: `M ${x1} ${p.top - base.top - 2} C ${x1} ${top - h}, ${x2} ${top - h}, ${x2} ${q.top - base.top - 4}`, tone, heads,
          label: text ? { x: (x1 + x2) / 2, y: top - h * 0.78, text } : undefined, cross: cross ? { x: (x1 + x2) / 2, y: top - h * 0.75 } : undefined });
        return;
      }
      const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy) || 1;
      const s = Math.min(p.width, p.height) / 2, t = Math.min(q.width, q.height) / 2 + 4;
      const ax = x1 + dx / len * s, ay = y1 + dy / len * s, bx = x2 - dx / len * t, by = y2 - dy / len * t;
      const cx = (ax + bx) / 2 - dy * 0.18, cy = (ay + by) / 2 + dx * 0.18;
      shapes.push({ d: `M ${ax} ${ay} Q ${cx} ${cy} ${bx} ${by}`, tone, heads,
        label: text ? { x: cx, y: cy - 6, text } : undefined, cross: cross ? { x: (ax + 2 * cx + bx) / 4, y: (ay + 2 * cy + by) / 4 } : undefined });
    };
    for (const a of actions) {
      switch (a.kind) {
        case 'flow': for (const f of a.arrows) link(f.from, f.to, 'value', 'end'); break;
        case 'swap':
          link(a.from, a.to, 'swap', 'both', 'swap');
          if ('cell' in a.from && 'cell' in a.to) link({ heapNode: a.from.cell }, { heapNode: a.to.cell }, 'swap', 'both', 'swap');
          break;
        case 'rewire':
          if (a.old) link({ node: a.object }, { node: a.old }, 'old', 'end', undefined, true);
          if (a.now) link({ node: a.object }, { node: a.now }, 'new', 'end', a.field);
          break;
        case 'move': link({ node: a.from }, { node: a.to }, 'move', 'end', a.name); break;
        case 'compare': link(a.a, a.b, a.verdict ? 'yes' : 'no', 'none', a.text); break;
        case 'return': link({ callNode: a.call }, a.to, 'return', 'end', a.text); break;
        case 'slot': link(a.from, a.to, 'slot', 'end', a.text); break;
        case 'stack': {
          const r = rect({ box: a.box });
          if (!r) break;
          const x = r.right - base.left - 18, y = r.top - base.top;
          shapes.push(a.dir === 'in'
            ? { d: `M ${x + 26} ${y - 30} Q ${x + 6} ${y - 24} ${x} ${y + 6}`, tone: 'stack', heads: 'end', label: { x: x + 30, y: y - 34, text: a.text } }
            : { d: `M ${x} ${y + 6} Q ${x + 6} ${y - 24} ${x + 26} ${y - 30}`, tone: 'stack', heads: 'end', label: { x: x + 30, y: y - 34, text: a.text } });
          break;
        }
        case 'ghost': {
          const r = rect(a.at);
          if (r) ghosts.push({ x: r.right - base.left + 4, y: r.top - base.top + 4, text: a.text });
          break;
        }
      }
    }
    const next = { shapes, ghosts };
    if (JSON.stringify(next) !== JSON.stringify(drawn)) setDrawn(next);
  });
  const tones: Tone[] = ['value', 'swap', 'old', 'new', 'move', 'yes', 'no', 'return', 'stack', 'slot'];
  return (
    <svg ref={svg} className="action-overlay" aria-hidden="true" data-testid="action-overlay" data-count={drawn.shapes.length}
         data-kinds={[...new Set(actions.map(a => a.kind))].join(' ')}>
      <defs>
        {tones.map(t => (
          <marker key={t} id={`act-${t}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className={`act-head ${t}`} />
          </marker>
        ))}
      </defs>
      {drawn.shapes.map((s, i) => (
        <g key={i} className={`action ${s.tone}`} data-testid={`action-${s.tone}`}>
          <path d={s.d} markerEnd={s.heads !== 'none' ? `url(#act-${s.tone})` : undefined} markerStart={s.heads === 'both' ? `url(#act-${s.tone})` : undefined} />
          {s.cross && <text x={s.cross.x} y={s.cross.y + 4} className="action-cross">✕</text>}
          {s.label && <text x={s.label.x} y={s.label.y} className="action-label">{s.label.text}</text>}
        </g>
      ))}
      {drawn.ghosts.map((g, i) => (
        <g key={`g${i}`} className="action-ghost" data-testid="action-ghost">
          <text x={g.x} y={g.y + 10}>{g.text}</text>
          <line x1={g.x - 1} y1={g.y + 6} x2={g.x + g.text.length * 7 + 1} y2={g.y + 6} />
        </g>
      ))}
    </svg>
  );
}
