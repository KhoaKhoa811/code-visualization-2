// trace-v1 wire types (contracts/trace/v1/trace.schema.json, specs/trace-format.md).
// Runtime facts only: no layout or presentation fields.

export type Value =
  | { kind: 'byte' | 'short' | 'int' | 'long'; value: string }
  | { kind: 'char'; value: number }
  | { kind: 'boolean'; value: boolean }
  | { kind: 'float' | 'double'; bits: string }
  | { kind: 'ref'; objectId: string }
  | { kind: 'null' }
  | { kind: 'unassigned' }
  // 1.1.0: state that uncaptured code may have changed and nothing has observed since.
  | { kind: 'unknown' };

export interface SourceRange { start: number; end: number }

export type TraceObject =
  | { objectId: string; kind: 'array'; elementType: string; elements: Value[] }
  | { objectId: string; kind: 'string'; utf16: number[] };

export interface Frame { frameId: string; methodId: string; callerFrameId: string | null }
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
  | { kind: 'EXCEPTION'; data: { exceptionType: string; message: string } }
  | { kind: 'UNCAPTURED'; data: { unknownBindings: string[]; arrays: { objectId: string; elements: Value[] }[]; unknownArrays: string[] } }
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
  schemaVersion: '1.1.0';
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
  'ARRAY_WRITE', 'EXPRESSION', 'CALL', 'RETURN', 'EXCEPTION', 'UNCAPTURED']);
export const BOOKKEEPING_KINDS = new Set(['SCOPE_ENTER', 'SCOPE_EXIT', 'FRAME_END', 'OBJECT_CAPTURE']);

export function isObservable(event: TraceEvent): event is ObservableEvent {
  return OBSERVABLE_KINDS.has(event.kind);
}
