// Visual layout of the playback panels (Part 9a): panel order in the grid and drag offsets
// of diagram elements. Layout is presentation only: it never touches trace data, state
// or the playback cursor (requirements section 3, AC-18, AC-23).

export interface Offset { x: number; y: number }

export interface Layout {
  /** Panel keys in grid order. */
  order: string[];
  /** Drag offsets by element key, such as object:o3 or array:o2; keys use the current run's ids. */
  offsets: Record<string, Offset>;
}

export const PANELS = ['variables', 'collections', 'objects', 'arrays', 'strings'] as const;

export function initialLayout(): Layout {
  return { order: [...PANELS], offsets: {} };
}

/** Moves panel {@code from} to the position of {@code to}; the panels between shift by one. */
export function movePanel(layout: Layout, from: string, to: string): Layout {
  if (from === to || !layout.order.includes(from) || !layout.order.includes(to)) return layout;
  const order = layout.order.filter(key => key !== from);
  order.splice(layout.order.indexOf(to), 0, from);
  return { ...layout, order };
}

export function setOffset(layout: Layout, key: string, x: number, y: number): Layout {
  const previous = layout.offsets[key];
  if (previous && previous.x === x && previous.y === y) return layout;
  return { ...layout, offsets: { ...layout.offsets, [key]: { x, y } } };
}

/** A new run: element ids of the previous run mean nothing now, so offsets are dropped; panel order stays. */
export function forNewRun(layout: Layout): Layout {
  return Object.keys(layout.offsets).length === 0 ? layout : { ...layout, offsets: {} };
}

/** CSS order of a panel in the grid. */
export function panelOrder(layout: Layout, key: string): number {
  const index = layout.order.indexOf(key);
  return index < 0 ? layout.order.length : index;
}
