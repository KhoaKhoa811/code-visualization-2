// Visual layout of the playback panels (Part 9a): panel order in the grid and drag offsets
// of diagram elements. Layout is presentation only: it never touches trace data, state
// or the playback cursor (requirements section 3, AC-18, AC-23).

export interface Offset { x: number; y: number }

export interface Layout {
  /** Panel keys in grid order. */
  order: string[];
  /** Drag offsets by element key, such as object:o3 or array:o2; keys use the current run's ids. */
  offsets: Record<string, Offset>;
  /** Part 9d: the chosen view of a deque or linked list, by variable name (it survives runs). */
  modes: Record<string, CollectionMode>;
}

export type CollectionMode = 'stack' | 'queue';

export const PANELS = ['variables', 'calls', 'collections', 'trees', 'structures', 'heaps', 'objects', 'arrays', 'strings'] as const;

export function initialLayout(): Layout {
  return { order: [...PANELS], offsets: {}, modes: {} };
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

/** Shows a deque or linked list (by variable name) as a stack or a queue; null restores its own view. */
export function setMode(layout: Layout, name: string, mode: CollectionMode | null): Layout {
  const modes = { ...layout.modes };
  if (mode === null) delete modes[name]; else modes[name] = mode;
  return { ...layout, modes };
}

/** CSS order of a panel in the grid. */
export function panelOrder(layout: Layout, key: string): number {
  const index = layout.order.indexOf(key);
  return index < 0 ? layout.order.length : index;
}
