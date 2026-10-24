// Part 21: the step's flow arrows over every panel: from each place the stored value was read to
// where it was stored, and a double arrow between two cells a swap exchanged (also between the two
// nodes of a heap view of that array). Placed from the measured elements after layout; an end that
// is not drawn (hidden panel, scrolled away) draws no arrow.
import { useLayoutEffect, useRef, useState } from 'react';
import type { FlowArrow } from '../trace/flows.ts';
import { elementOf, type Anchor } from './motion.ts';

interface Drawn { d: string; kind: FlowArrow['kind']; label?: { x: number; y: number } }

export function FlowOverlay({ arrows }: { arrows: FlowArrow[] }) {
  const svg = useRef<SVGSVGElement>(null);
  const [drawn, setDrawn] = useState<Drawn[]>([]);
  useLayoutEffect(() => {
    const host = svg.current?.parentElement;
    if (!host) return;
    const base = host.getBoundingClientRect();
    const rect = (a: Anchor) => {
      const el = elementOf(a);
      if (!el || !host.contains(el)) return null;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 ? r : null;
    };
    // A swap of array cells is also drawn between the two nodes of a heap view of that array.
    const all = arrows.flatMap(a => a.kind === 'swap' && 'cell' in a.from && 'cell' in a.to
      ? [a, { from: { heapNode: a.from.cell }, to: { heapNode: a.to.cell }, kind: 'swap' as const }] : [a]);
    const next: Drawn[] = all.flatMap(a => {
      const p = rect(a.from), q = rect(a.to);
      if (!p || !q) return [];
      const x1 = p.left + p.width / 2 - base.left, y1 = p.top + p.height / 2 - base.top;
      const x2 = q.left + q.width / 2 - base.left, y2 = q.top + q.height / 2 - base.top;
      if (Math.abs(y2 - y1) < 8) { // the same row (cells, items): an arc over the tops, high enough to read
        const top = Math.min(p.top, q.top) - base.top, h = Math.max(a.kind === 'swap' ? 34 : 24, Math.abs(x2 - x1) * 0.35);
        const label = { x: (x1 + x2) / 2, y: top - h * 0.75 };
        return [{ d: `M ${x1} ${p.top - base.top - 2} C ${x1} ${top - h}, ${x2} ${top - h}, ${x2} ${q.top - base.top - 4}`, kind: a.kind,
          label: a.kind === 'swap' ? label : undefined }];
      }
      const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy) || 1;
      // Leave the boxes' centres free: start and end a little outside them, on the line between them.
      const s = Math.min(p.width, p.height) / 2, t = Math.min(q.width, q.height) / 2 + 4;
      const ax = x1 + dx / len * s, ay = y1 + dy / len * s, bx = x2 - dx / len * t, by = y2 - dy / len * t;
      const bend = a.kind === 'swap' ? 0.35 : 0.18;
      const cx = (ax + bx) / 2 - dy * bend, cy = (ay + by) / 2 + dx * bend - (Math.abs(dy) < 8 ? 24 : 0);
      return [{ d: `M ${ax} ${ay} Q ${cx} ${cy} ${bx} ${by}`, kind: a.kind, label: a.kind === 'swap' ? { x: cx, y: cy } : undefined }];
    });
    if (JSON.stringify(next) !== JSON.stringify(drawn)) setDrawn(next);
  });
  return (
    <svg ref={svg} className="flow-overlay" aria-hidden="true" data-testid="flow-overlay" data-count={drawn.length}
         data-swaps={drawn.filter(d => d.kind === 'swap').length}>
      <defs>
        <marker id="flow-value" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" /></marker>
        <marker id="flow-swap" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" /></marker>
      </defs>
      {drawn.map((a, i) => (
        <g key={i} className={`flow-arrow ${a.kind}`}>
          <path d={a.d} markerEnd={`url(#flow-${a.kind})`} markerStart={a.kind === 'swap' ? 'url(#flow-swap)' : undefined} />
          {a.label && <text x={a.label.x} y={a.label.y - 4} className="flow-label">swap</text>}
        </g>
      ))}
    </svg>
  );
}
