// Layout-only dragging (Part 9a): panels reorder by dragging their header onto another
// panel; diagram elements move by a pointer drag. Neither changes values, references or
// the playback cursor.
import { createContext, useContext, useRef, type HTMLAttributes, type ReactNode } from 'react';
import { movePanel, panelOrder, setOffset, type Layout } from './layout.ts';

interface LayoutApi { layout: Layout; update: (change: (layout: Layout) => Layout) => void }

export const LayoutContext = createContext<LayoutApi | null>(null);

function useLayout(): LayoutApi {
  const api = useContext(LayoutContext);
  if (!api) throw new Error('LayoutContext missing');
  return api;
}

const PANEL_TYPE = 'application/x-codeviz-panel';

/** A grid panel; its header is the drag handle for reordering. */
export function Panel({ id, title, label, testId, children }: { id: string; title: ReactNode; label: string; testId?: string; children: ReactNode }) {
  const { layout, update } = useLayout();
  return (
    <section className="panel" aria-label={label} data-panel={id} data-testid={testId} style={{ order: panelOrder(layout, id) }}
             onDragOver={e => { if (e.dataTransfer.types.includes(PANEL_TYPE)) e.preventDefault(); }}
             onDrop={e => {
               const from = e.dataTransfer.getData(PANEL_TYPE);
               if (!from) return;
               e.preventDefault();
               update(l => movePanel(l, from, id));
             }}>
      <h2 className="panel-handle" draggable title="Drag onto another panel to reorder"
          onDragStart={e => { e.dataTransfer.setData(PANEL_TYPE, id); e.dataTransfer.effectAllowed = 'move'; }}>
        {title}
      </h2>
      {children}
    </section>
  );
}

/** A diagram element moved by pointer drag; its offset is kept under {@code id} until the next run. */
export function Draggable({ id, className, style, children, ...rest }: { id: string } & HTMLAttributes<HTMLDivElement>) {
  const { layout, update } = useLayout();
  const offset = layout.offsets[id] ?? { x: 0, y: 0 };
  const drag = useRef<{ x: number; y: number; base: { x: number; y: number } } | null>(null);
  return (
    <div {...rest} className={`${className ?? ''} draggable`} data-drag={id}
         style={{ ...style, transform: offset.x || offset.y ? `translate(${offset.x}px, ${offset.y}px)` : undefined }}
         onPointerDown={e => {
           if (e.button !== 0) return;
           e.stopPropagation(); // the innermost element moves, not the one containing it
           e.currentTarget.setPointerCapture(e.pointerId);
           drag.current = { x: e.clientX, y: e.clientY, base: offset };
         }}
         onPointerMove={e => {
           const d = drag.current;
           if (d) update(l => setOffset(l, id, d.base.x + e.clientX - d.x, d.base.y + e.clientY - d.y));
         }}
         onPointerUp={() => { drag.current = null; }}
         onPointerCancel={() => { drag.current = null; }}>
      {children}
    </div>
  );
}
