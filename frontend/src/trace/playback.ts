// Recorded playback (specs/trace-format.md sections 3 and 10): cursor k is
// initialState plus events up to stepEnds[k-1]. Never executes Java and never
// reads beyond the cursor's boundary. Checkpoints are equivalent caches.
import {
  BOOKKEEPING_KINDS, OBSERVABLE_KINDS, isObservable,
  type Binding, type Frame, type ObservableEvent, type Scope, type Trace, type TraceEvent, type TraceObject, type Value,
} from './types.ts';

export class TraceError extends Error {}

export interface State {
  objects: Map<string, TraceObject>;
  frames: Frame[];
  scopes: Scope[];
  bindings: Binding[];
}

/** What the kth operation touched, for highlighting. */
export interface Focus {
  bindingId?: string;
  objectId?: string;
  index?: number;
  access?: 'read' | 'write' | 'length' | 'allocate';
  /** 1.4.0: the field a FIELD_READ/FIELD_WRITE touched. */
  field?: string;
  /** Bindings and arrays an uncaptured statement changed or made unknown. */
  touched?: string[];
}

export interface View {
  cursor: number;
  state: State;
  event: ObservableEvent | null;
  focus: Focus;
  terminal: boolean;
  /** At a terminal cursor whose cleanup ended the frames: the recorded state just before it. */
  beforeEnd: State | null;
}


export class Playback {
  readonly trace: Trace;
  /** Number of observable operations; cursors run from 0 to steps. */
  readonly steps: number;
  private readonly operations: ObservableEvent[] = [];
  private readonly checkpoints = new Map<number, State>();

  private readonly every: number;

  constructor(trace: Trace, checkpointEvery = 256) {
    this.every = checkpointEvery;
    if (trace.schemaVersion !== '1.4.0') throw new TraceError(`Unsupported trace version ${String(trace.schemaVersion)}`);
    this.trace = trace;
    this.steps = trace.stepEnds.length;
    if (trace.initialState === null) {
      if (trace.events.length || this.steps) throw new TraceError('Unavailable capture must have no events');
      return;
    }
    trace.events.forEach((event, index) => {
      if (event.seq !== index + 1) throw new TraceError(`Event ${index + 1} has seq ${event.seq}`);
      if (!OBSERVABLE_KINDS.has(event.kind) && !BOOKKEEPING_KINDS.has(event.kind)) {
        throw new TraceError(`Unknown event kind ${event.kind}`);
      }
      if (isObservable(event)) {
        if (event.step !== this.operations.length + 1) throw new TraceError(`Step ${event.step} out of order`);
        if (event.source.start > event.source.end || event.source.end > trace.source.text.length) {
          throw new TraceError(`Step ${event.step} source range outside the source`);
        }
        this.operations.push(event);
      }
    });
    if (this.operations.length !== this.steps) throw new TraceError('stepEnds does not match the observable events');
    this.operations.forEach((event, i) => {
      const final = i === this.steps - 1 && trace.capture.status === 'complete';
      if (trace.stepEnds[i] !== (final ? trace.events.length : event.seq)) throw new TraceError(`Bad boundary for step ${i + 1}`);
    });
    // One validating forward pass; it also fills the checkpoints.
    const seen = new Set<string>();
    const state = initial(trace, seen);
    this.checkpoints.set(0, clone(state));
    let applied = 0;
    for (; applied < this.boundary(0); applied++) apply(state, trace.events[applied], seen); // zero-operation terminal cleanup
    for (let k = 1; k <= this.steps; k++) {
      const end = this.boundary(k);
      for (; applied < end; applied++) apply(state, trace.events[applied], seen);
      if (k % this.every === 0) this.checkpoints.set(k, clone(state));
    }
  }

  get available(): boolean { return this.trace.initialState !== null; }

  /** Number of events applied at cursor k. */
  boundary(k: number): number {
    if (k === 0) return this.steps === 0 && this.trace.capture.status === 'complete' ? this.trace.events.length : 0;
    return this.trace.stepEnds[k - 1];
  }

  at(cursor: number): View {
    if (!this.available) throw new TraceError('Capture unavailable');
    const k = Math.max(0, Math.min(this.steps, Math.trunc(cursor)));
    const base = Math.floor(k / this.every) * this.every;
    const state = clone(this.checkpoints.get(base)!);
    const end = this.boundary(k);
    // Checkpoint 0 is the initial state; checkpoint c > 0 already includes boundary(c).
    for (let i = base === 0 ? 0 : this.boundary(base); i < end; i++) apply(state, this.trace.events[i]);
    const event = k === 0 ? null : this.operations[k - 1];
    let beforeEnd: State | null = null;
    if (event && k === this.steps && end > event.seq && state.frames.length === 0) {
      const from = Math.floor((k - 1) / this.every) * this.every; // checkpoint before the cleanup
      beforeEnd = clone(this.checkpoints.get(from)!);
      for (let i = from === 0 ? 0 : this.boundary(from); i < event.seq; i++) apply(beforeEnd, this.trace.events[i]);
    }
    return { cursor: k, state, event, focus: focusOf(event), terminal: k === this.steps, beforeEnd };
  }
}

function initial(trace: Trace, seen?: Set<string>): State {
  const init = trace.initialState!;
  const state: State = { objects: new Map(), frames: [], scopes: [], bindings: [] };
  for (const object of init.objects) addObject(state, object, seen);
  for (const frame of init.frames) { fresh(frame.frameId, seen); state.frames.push({ ...frame }); }
  for (const scope of init.scopes) { fresh(scope.scopeId, seen); state.scopes.push({ ...scope }); }
  for (const binding of init.bindings) { fresh(binding.bindingId, seen); resolves(state, binding.value); state.bindings.push({ ...binding }); }
  return state;
}

function apply(state: State, event: TraceEvent, seen?: Set<string>): void {
  switch (event.kind) {
    case 'DECLARE': {
      const binding = event.data.binding;
      activeFrame(state, event.frameId);
      if (!state.scopes.some(s => s.scopeId === binding.scopeId)) throw new TraceError(`Unknown scope ${binding.scopeId}`);
      fresh(binding.bindingId, seen);
      resolves(state, binding.value);
      state.bindings.push({ ...binding });
      return;
    }
    case 'ASSIGN':
    case 'UPDATE': {
      const binding = findBinding(state, event.data.bindingId);
      expectSame(binding.value, event.data.previous, `previous value of ${binding.name}`);
      resolves(state, event.data.value);
      binding.value = event.data.value;
      return;
    }
    case 'ALLOCATE':
    case 'OBJECT_CAPTURE':
      addObject(state, event.data.object, seen);
      return;
    case 'ARRAY_READ': {
      const array = findArray(state, event.data.objectId, event.data.index);
      expectSame(array.elements[event.data.index], event.data.value, 'array read');
      return;
    }
    case 'ARRAY_LENGTH':
      if (findArray(state, event.data.objectId).elements.length !== event.data.length) throw new TraceError('Array length mismatch');
      return;
    case 'ARRAY_WRITE': {
      const array = findArray(state, event.data.objectId, event.data.index);
      expectSame(array.elements[event.data.index], event.data.previous, 'array previous value');
      array.elements[event.data.index] = event.data.value;
      return;
    }
    case 'EXPRESSION':
    case 'EXCEPTION':
    case 'SWITCH':
      return;
    case 'FIELD_READ': {
      const field = findField(state, event.data.objectId, event.data.field);
      expectSame(field.value, event.data.value, 'field read');
      return;
    }
    case 'FIELD_WRITE': {
      const field = findField(state, event.data.objectId, event.data.field);
      expectSame(field.value, event.data.previous, 'field previous value');
      resolves(state, event.data.value);
      field.value = event.data.value;
      return;
    }
    case 'UNCAPTURED': {
      activeFrame(state, event.frameId);
      for (const bindingId of event.data.unknownBindings) findBinding(state, bindingId).value = { kind: 'unknown' };
      for (const { objectId, elements } of event.data.arrays) {
        const array = findArray(state, objectId);
        if (array.elements.length !== elements.length) throw new TraceError('Re-read array changed length');
        array.elements = [...elements];
      }
      for (const objectId of event.data.unknownArrays) {
        const array = findArray(state, objectId);
        array.elements = array.elements.map(() => ({ kind: 'unknown' }));
      }
      for (const { objectId, fields } of event.data.objects) {
        for (const { name, value } of fields) findField(state, objectId, name).value = value;
      }
      for (const objectId of event.data.unknownObjects) {
        const object = state.objects.get(objectId);
        if (object?.kind !== 'object') throw new TraceError(`Unknown object ${objectId}`);
        for (const field of object.fields) field.value = { kind: 'unknown' };
      }
      return;
    }
    case 'CALL':
      fresh(event.data.frame.frameId, seen);
      fresh(event.data.scope.scopeId, seen);
      state.frames.push({ ...event.data.frame });
      state.scopes.push({ ...event.data.scope });
      for (const parameter of event.data.parameters) { fresh(parameter.bindingId, seen); state.bindings.push({ ...parameter }); }
      return;
    case 'RETURN':
      activeFrame(state, event.frameId);
      popFrame(state, event.frameId);
      return;
    case 'SCOPE_ENTER':
      fresh(event.data.scope.scopeId, seen);
      state.scopes.push({ ...event.data.scope });
      return;
    case 'SCOPE_EXIT': {
      const index = state.scopes.findIndex(s => s.scopeId === event.data.scopeId);
      if (index < 0) throw new TraceError(`Unknown scope ${event.data.scopeId}`);
      state.scopes.splice(index, 1);
      state.bindings = state.bindings.filter(b => b.scopeId !== event.data.scopeId);
      return;
    }
    case 'FRAME_END':
      activeFrame(state, event.data.frameId);
      popFrame(state, event.data.frameId);
      return;
  }
}

function popFrame(state: State, frameId: string): void {
  state.frames.pop();
  state.scopes = state.scopes.filter(s => s.frameId !== frameId);
  state.bindings = state.bindings.filter(b => b.frameId !== frameId);
}

function activeFrame(state: State, frameId: string): void {
  if (state.frames.at(-1)?.frameId !== frameId) throw new TraceError(`Frame ${frameId} is not active`);
}

function findBinding(state: State, bindingId: string): Binding {
  const binding = state.bindings.find(b => b.bindingId === bindingId);
  if (!binding) throw new TraceError(`Unknown binding ${bindingId}`);
  return binding;
}

function findArray(state: State, objectId: string, index?: number): TraceObject & { kind: 'array' } {
  const object = state.objects.get(objectId);
  if (!object || object.kind !== 'array') throw new TraceError(`Unknown array ${objectId}`);
  if (index !== undefined && (index < 0 || index >= object.elements.length)) throw new TraceError(`Index ${index} outside ${objectId}`);
  return object;
}

function findField(state: State, objectId: string, name: string) {
  const object = state.objects.get(objectId);
  if (!object || object.kind !== 'object') throw new TraceError(`Unknown object ${objectId}`);
  const field = object.fields.find(f => f.name === name);
  if (!field) throw new TraceError(`Unknown field ${name} of ${objectId}`);
  return field;
}

function addObject(state: State, object: TraceObject, seen?: Set<string>): void {
  fresh(object.objectId, seen);
  state.objects.set(object.objectId, copyObject(object));
}

/** Arrays and objects change in place during replay, so every state holds its own copies. */
function copyObject(object: TraceObject): TraceObject {
  if (object.kind === 'array') return { ...object, elements: [...object.elements] };
  if (object.kind === 'object') return { ...object, fields: object.fields.map(f => ({ ...f })) };
  return object;
}

function resolves(state: State, value: Value): void {
  if (value.kind === 'ref' && !state.objects.has(value.objectId)) throw new TraceError(`Unknown object ${value.objectId}`);
}

function fresh(id: string, seen?: Set<string>): void {
  if (!seen) return;
  if (seen.has(id)) throw new TraceError(`Identity ${id} reused`);
  seen.add(id);
}

export function sameValue(a: Value, b: Value): boolean {
  if (a.kind !== b.kind) return false;
  if ('value' in a && 'value' in b) return a.value === b.value;
  if ('bits' in a && 'bits' in b) return a.bits === b.bits;
  if (a.kind === 'ref' && b.kind === 'ref') return a.objectId === b.objectId;
  return true;
}

function expectSame(actual: Value | undefined, recorded: Value, what: string): void {
  if (!actual || !sameValue(actual, recorded)) throw new TraceError(`Recorded ${what} disagrees with reconstructed state`);
}

function clone(state: State): State {
  const objects = new Map<string, TraceObject>();
  for (const [id, object] of state.objects) objects.set(id, copyObject(object));
  return {
    objects,
    frames: state.frames.map(f => ({ ...f })),
    scopes: state.scopes.map(s => ({ ...s })),
    bindings: state.bindings.map(b => ({ ...b })),
  };
}

function focusOf(event: ObservableEvent | null): Focus {
  if (!event) return {};
  switch (event.kind) {
    case 'DECLARE': return { bindingId: event.data.binding.bindingId };
    case 'ASSIGN': case 'UPDATE': return { bindingId: event.data.bindingId };
    case 'ALLOCATE': return { objectId: event.data.object.objectId, access: 'allocate' };
    case 'ARRAY_READ': return { objectId: event.data.objectId, index: event.data.index, access: 'read' };
    case 'ARRAY_WRITE': return { objectId: event.data.objectId, index: event.data.index, access: 'write' };
    case 'ARRAY_LENGTH': return { objectId: event.data.objectId, access: 'length' };
    case 'FIELD_READ': return { objectId: event.data.objectId, field: event.data.field, access: 'read' };
    case 'FIELD_WRITE': return { objectId: event.data.objectId, field: event.data.field, access: 'write' };
    case 'UNCAPTURED': return { touched: [...event.data.unknownBindings, ...event.data.arrays.map(a => a.objectId), ...event.data.unknownArrays,
      ...event.data.objects.map(o => o.objectId), ...event.data.unknownObjects] };
    default: return {};
  }
}
