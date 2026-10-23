// trace-v1 wire types (contracts/trace/v1/trace.schema.json, specs/trace-format.md).
// Runtime facts only: no layout or presentation fields.

export type Value =
  | { kind: 'byte' | 'short' | 'int' | 'long'; value: string }
  | { kind: 'char'; value: number }
  | { kind: 'boolean'; value: boolean }
  | { kind: 'float' | 'double'; bits: string }
  | { kind: 'ref'; objectId: string }
  | { kind: 'null' }
  // 1.10.0: a lambda, method reference or library-made function, shown by its label
  | { kind: 'function' | 'stream' | 'optional'; label: string }
  // 1.11.0: an iterator used directly, shown by the call that made it; objectId is the collection it iterates
  | { kind: 'iterator'; label: string; objectId: string }
  | { kind: 'unassigned' }
  // 1.1.0: state that uncaptured code may have changed and nothing has observed since.
  | { kind: 'unknown' };

export interface SourceRange { start: number; end: number }

export type TraceObject =
  | { objectId: string; kind: 'array'; elementType: string; elements: Value[] }
  | { objectId: string; kind: 'string'; utf16: number[] }
  // 1.4.0: an instance of a traced class, fields in declaration order.
  | { objectId: string; kind: 'object'; className: string; fields: Field[] }
  // 1.5.0: library objects. Elements/entries in iteration order, ascending for HashSet/HashMap, poll order for PriorityQueue.
  | { objectId: string; kind: 'collection'; className: string; elements: Value[] }
  | { objectId: string; kind: 'map'; className: string; entries: { key: Value; value: Value }[] }
  | { objectId: string; kind: 'builder'; className: string; utf16: number[] }
  // 1.6.0: a Scanner reading standard input. Its reads are LIBRARY steps; it has no other state.
  | { objectId: string; kind: 'input'; className: 'java.util.Scanner' | 'java.io.BufferedReader' } // 1.8.0: BufferedReader
  // 1.7.0: a thrown exception: its class (binary name) and message when first seen.
  | { objectId: string; kind: 'exception'; className: string; message: string | null }
  // 1.9.0: a map entry from a for-each over entrySet(), with the key and value it had when obtained.
  | { objectId: string; kind: 'entry'; mapId: string; key: Value; value: Value };

export interface Field { name: string; javaType: string; value: Value }

// 1.10.0: via is the library method that ran this frame's function (a lambda body or Main::helper).
export interface Frame { frameId: string; methodId: string; callerFrameId: string | null; via?: string }
export interface Scope { scopeId: string; frameId: string; parentScopeId: string | null }
export interface Binding {
  bindingId: string; declarationId: string; frameId: string; scopeId: string;
  name: string; javaType: string; value: Value;
}

export interface InitialState {
  objects: TraceObject[]; frames: Frame[]; scopes: Scope[]; bindings: Binding[];
}

export type Operand = { value: Value } | { skipped: true };

export type ObservableEvent = { seq: number; step: number; source: SourceRange; frameId: string } & (
  | { kind: 'DECLARE'; data: { binding: Binding } }
  | { kind: 'ASSIGN'; data: { bindingId: string; previous: Value; value: Value } }
  | { kind: 'UPDATE'; data: { bindingId: string; previous: Value; value: Value; result: Value; operator: '++' | '--'; position: 'prefix' | 'postfix' } }
  | { kind: 'ALLOCATE'; data: { object: TraceObject } }
  | { kind: 'ARRAY_READ'; data: { objectId: string; index: number; value: Value } }
  | { kind: 'ARRAY_LENGTH'; data: { objectId: string; length: number } }
  | { kind: 'ARRAY_WRITE'; data: { objectId: string; index: number; previous: Value; value: Value } }
  | { kind: 'EXPRESSION'; data: { operator: string; operands: Operand[]; result: Value } }
  | { kind: 'CALL'; data: { frame: Frame; scope: Scope; parameters: Binding[] } }
  | { kind: 'RETURN'; data: { result: Value | null; implicit: boolean } }
  | { kind: 'EXCEPTION'; data: { exceptionType: string; message: string; objectId: string } }
  // 1.7.0: a catch clause received the exception in flight; its parameter binding refers to it.
  | { kind: 'CATCH'; data: { binding: Binding } }
  | { kind: 'UNCAPTURED'; data: { unknownBindings: string[]; arrays: { objectId: string; elements: Value[] }[]; unknownArrays: string[];
      objects: { objectId: string; fields: { name: string; value: Value }[] }[]; unknownObjects: string[];
      collections: TraceObject[]; unknownCollections: string[] } }
  // 1.9.0: java.util.Iterator.next() is one for-each element of objectId; view names a map view.
  | { kind: 'LIBRARY'; data: { objectId: string | null; method: string; arguments: Value[]; result: Value | null; after: TraceObject | null; view?: 'keys' | 'values' | 'entries' } }
  | { kind: 'FIELD_READ'; data: { objectId: string; field: string; value: Value } }
  | { kind: 'FIELD_WRITE'; data: { objectId: string; field: string; previous: Value; value: Value } }
  // 1.3.0: a switch chose a branch; the source range is the chosen label.
  | { kind: 'SWITCH'; data: { value: Value; matched: 'case' | 'default' | 'none' } }
);

export type BookkeepingEvent = { seq: number } & (
  | { kind: 'SCOPE_ENTER'; data: { scope: Scope } }
  | { kind: 'SCOPE_EXIT'; data: { scopeId: string } }
  | { kind: 'FRAME_END'; data: { frameId: string; reason: 'main_return' | 'unwind' } }
  | { kind: 'OBJECT_CAPTURE'; data: { object: TraceObject } }
);

export type TraceEvent = ObservableEvent | BookkeepingEvent;

export type ExecutionStatus = 'completed' | 'failed' | 'cancelled' | 'limited' | 'compile_error' | 'rejected';
export type CaptureStatus = 'complete' | 'partial' | 'unavailable';

export interface Diagnostic {
  phase: 'compile' | 'admission' | 'capture' | 'execution';
  code: string; message: string; source?: SourceRange;
}

export interface Trace {
  schemaVersion: '1.11.0';
  runId: string;
  source: { file: 'Main.java'; text: string; sha256: string };
  execution: { status: ExecutionStatus; reason: string };
  capture: { status: CaptureStatus; reason: string; safeEventCount: number };
  diagnostics: Diagnostic[];
  initialState: InitialState | null;
  events: TraceEvent[];
  stepEnds: number[];
}

export const OBSERVABLE_KINDS = new Set(['DECLARE', 'ASSIGN', 'UPDATE', 'ALLOCATE', 'ARRAY_READ', 'ARRAY_LENGTH',
  'ARRAY_WRITE', 'EXPRESSION', 'CALL', 'RETURN', 'EXCEPTION', 'UNCAPTURED', 'SWITCH', 'FIELD_READ', 'FIELD_WRITE', 'LIBRARY', 'CATCH']);
export const BOOKKEEPING_KINDS = new Set(['SCOPE_ENTER', 'SCOPE_EXIT', 'FRAME_END', 'OBJECT_CAPTURE']);

export function isObservable(event: TraceEvent): event is ObservableEvent {
  return OBSERVABLE_KINDS.has(event.kind);
}
