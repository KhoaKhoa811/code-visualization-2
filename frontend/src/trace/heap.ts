// Array-as-heap projection (Part 9c, specs/heap-view.md). The user confirms which array
// variable is a heap, its indexing, which variable (if any) bounds the active heap and the
// order to observe. Nothing is inferred from the algorithm. The heap is the same array object
// the Arrays panel shows; the projection only reads recorded state and never repairs it.
import type { State } from './playback.ts';
import type { Trace, TraceObject, Value } from './types.ts';

export interface HeapMapping {
  /** Variable that refers to the array (looked up from the outermost frame inward). */
  array: string;
  /** 0: children of i are 2i+1 and 2i+2; 1: index 0 is unused, children of i are 2i and 2i+1. */
  base: 0 | 1;
  /** Variable bounding the active heap, or null for the whole array. */
  size: string | null;
  /** How the size variable bounds the heap: a count (indices below it) or the last heap index. */
  bound: 'count' | 'last';
  /** The order whose violations are observed (never enforced). */
  order: 'max' | 'min';
}

type ArrayObject = TraceObject & { kind: 'array' };

const NUMERIC = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'char']);

/** Variable names in a trace: one-dimensional numeric arrays (heap candidates) and int variables (bounds). */
export function heapVariables(trace: Trace): { arrays: string[]; ints: string[] } {
  const arrays = new Set<string>(), ints = new Set<string>();
  const see = (name: string, javaType: string) => {
    if (javaType.endsWith('[]') && NUMERIC.has(javaType.slice(0, -2))) arrays.add(name);
    if (javaType === 'int') ints.add(name);
  };
  for (const b of trace.initialState?.bindings ?? []) see(b.name, b.javaType);
  for (const e of trace.events) {
    if (e.kind === 'DECLARE') see(e.data.binding.name, e.data.binding.javaType);
    if (e.kind === 'CALL') for (const p of e.data.parameters) see(p.name, p.javaType);
  }
  return { arrays: [...arrays], ints: [...ints] };
}

/** Why a mapping does not fit a trace (null when it fits). */
export function heapProblem(mapping: HeapMapping, trace: Trace): string | null {
  const vars = heapVariables(trace);
  if (!vars.arrays.includes(mapping.array)) return `no numeric array variable ${mapping.array} in this run`;
  if (mapping.size !== null && !vars.ints.includes(mapping.size)) return `no int variable ${mapping.size} in this run`;
  return null;
}

/** The value of the first binding with this name, outermost frame first (main before helpers). */
function lookup(state: State, name: string): Value | undefined {
  for (const frame of state.frames) {
    const b = state.bindings.find(x => x.frameId === frame.frameId && x.name === name);
    if (b) return b.value;
  }
  return undefined;
}

export interface HeapView {
  array: ArrayObject;
  /** Indices of the active heap; the rest of the array (from base) is the suffix outside it. */
  heap: number[];
  /** null when the size variable is not in scope or not known: no boundary is shown. */
  boundary: number | null;
  /** Why no boundary is shown, if none is. */
  boundaryNote: string | null;
  /** Parent-child pairs (indices) inside the heap that break the observed order. */
  violations: [number, number][];
}

export function children(mapping: HeapMapping, i: number): [number, number] {
  return mapping.base === 0 ? [2 * i + 1, 2 * i + 2] : [2 * i, 2 * i + 1];
}

function number(v: Value): number | null {
  if (v.kind === 'byte' || v.kind === 'short' || v.kind === 'int' || v.kind === 'long') return Number(v.value);
  if (v.kind === 'char') return v.value;
  if (v.kind === 'float' || v.kind === 'double') {
    const view = new DataView(new ArrayBuffer(8));
    if (v.bits.length === 8) { view.setUint32(0, parseInt(v.bits, 16)); return view.getFloat32(0); }
    view.setBigUint64(0, BigInt(`0x${v.bits}`));
    return view.getFloat64(0);
  }
  return null;
}

/** The heap at a state, or null when the array variable is not in scope at this cursor. */
export function projectHeap(state: State, mapping: HeapMapping): HeapView | null {
  const ref = lookup(state, mapping.array);
  if (!ref || ref.kind !== 'ref') return null;
  const array = state.objects.get(ref.objectId);
  if (!array || array.kind !== 'array') return null;
  const length = array.elements.length;
  let boundary: number | null = length;
  let boundaryNote: string | null = null;
  if (mapping.size !== null) {
    const v = lookup(state, mapping.size);
    const n = v ? number(v) : null;
    if (n === null) {
      boundary = null;
      boundaryNote = v?.kind === 'unknown' ? `${mapping.size} is unknown here` : `${mapping.size} is not in scope here`;
    } else {
      boundary = Math.max(mapping.base, Math.min(length, mapping.bound === 'count' ? n + mapping.base : n + 1));
    }
  }
  const end = boundary ?? length;
  const heap: number[] = [];
  for (let i = mapping.base; i < end; i++) heap.push(i);
  const violations: [number, number][] = [];
  for (const i of heap) {
    for (const c of children(mapping, i)) {
      if (c >= end) continue;
      const p = number(array.elements[i]), q = number(array.elements[c]);
      if (p === null || q === null) continue; // unknown values are not observations
      if (mapping.order === 'max' ? p < q : p > q) violations.push([i, c]);
    }
  }
  return { array, heap, boundary, boundaryNote, violations };
}

/** Tree positions of heap indices: x is the in-order rank, y the depth. */
export function heapPositions(mapping: HeapMapping, heap: number[]): Map<number, { x: number; y: number }> {
  const inHeap = new Set(heap);
  const out = new Map<number, { x: number; y: number }>();
  let next = 0;
  const visit = (i: number, depth: number) => {
    if (!inHeap.has(i)) return;
    const [l, r] = children(mapping, i);
    visit(l, depth + 1);
    out.set(i, { x: next++, y: depth });
    visit(r, depth + 1);
  };
  visit(mapping.base, 0);
  return out;
}
