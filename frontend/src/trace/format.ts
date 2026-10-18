// Display text for recorded values and operations. Presentation only: it never
// changes or infers runtime facts.
import type { State } from './playback.ts';
import type { ObservableEvent, Operand, TraceObject, Value } from './types.ts';

export function formatValue(value: Value, objects?: Map<string, TraceObject>): string {
  switch (value.kind) {
    case 'byte': case 'short': case 'int': return value.value;
    case 'long': return `${value.value}L`;
    case 'boolean': return String(value.value);
    case 'char': return `'${String.fromCharCode(value.value)}'`;
    case 'float': return `${fromBits(value.bits)}f`;
    case 'double': return fromBits(value.bits);
    case 'null': return 'null';
    case 'unassigned': return '(unassigned)';
    case 'unknown': return '?';
    case 'ref': {
      const object = objects?.get(value.objectId);
      if (object?.kind === 'string') return JSON.stringify(stringText(object));
      if (object?.kind === 'array') return `${object.elementType}[${object.elements.length}]`;
      return `ref ${value.objectId}`;
    }
  }
}

export function stringText(object: TraceObject & { kind: 'string' }): string {
  return String.fromCharCode(...object.utf16);
}

/** IEEE-754 bit pattern (8 or 16 hex digits) to its Java decimal-ish text. */
export function fromBits(bits: string): string {
  const view = new DataView(new ArrayBuffer(8));
  let number: number;
  if (bits.length === 8) { view.setUint32(0, parseInt(bits, 16)); number = view.getFloat32(0); }
  else { view.setBigUint64(0, BigInt(`0x${bits}`)); number = view.getFloat64(0); }
  if (Object.is(number, -0)) return '-0.0';
  if (Number.isNaN(number)) return 'NaN';
  if (!Number.isFinite(number)) return number > 0 ? 'Infinity' : '-Infinity';
  return Number.isInteger(number) ? number.toFixed(1) : String(number);
}

/** Names of the bindings that currently refer to an object (aliases share it). */
export function namesOf(state: State, objectId: string): string[] {
  return state.bindings.filter(b => b.value.kind === 'ref' && b.value.objectId === objectId).map(b => b.name);
}

function operand(o: Operand, objects: Map<string, TraceObject>): string {
  return 'skipped' in o ? '(not evaluated)' : formatValue(o.value, objects);
}

/** One-line description of the kth operation, read from the state at that cursor. */
export function describe(event: ObservableEvent, state: State): string {
  const objects = state.objects;
  const arrayName = (objectId: string) => namesOf(state, objectId)[0] ?? 'array';
  const bindingName = (bindingId: string) => state.bindings.find(b => b.bindingId === bindingId)?.name ?? 'variable';
  switch (event.kind) {
    case 'DECLARE': {
      const b = event.data.binding;
      return `Declare ${b.javaType} ${b.name} = ${formatValue(b.value, objects)}`;
    }
    case 'ASSIGN':
      return `Assign ${bindingName(event.data.bindingId)}: ${formatValue(event.data.previous, objects)} → ${formatValue(event.data.value, objects)}`;
    case 'UPDATE': {
      const name = bindingName(event.data.bindingId);
      const text = event.data.position === 'prefix' ? `${event.data.operator}${name}` : `${name}${event.data.operator}`;
      return `${text}: ${formatValue(event.data.previous)} → ${formatValue(event.data.value)} (result ${formatValue(event.data.result)})`;
    }
    case 'ALLOCATE': {
      const o = event.data.object;
      return o.kind === 'array' ? `Create ${o.elementType}[${o.elements.length}]` : 'Create string';
    }
    case 'ARRAY_READ':
      return `Read ${arrayName(event.data.objectId)}[${event.data.index}] = ${formatValue(event.data.value, objects)}`;
    case 'ARRAY_LENGTH':
      return `${arrayName(event.data.objectId)}.length = ${event.data.length}`;
    case 'ARRAY_WRITE':
      return `Write ${arrayName(event.data.objectId)}[${event.data.index}]: ${formatValue(event.data.previous, objects)} → ${formatValue(event.data.value, objects)}`;
    case 'EXPRESSION': {
      const [a, b] = event.data.operands;
      const result = formatValue(event.data.result, objects);
      if (event.data.operator === 'condition') return `Condition ${operand(a, objects)}`;
      if (b === undefined) return `${event.data.operator}${operand(a, objects)} = ${result}`;
      return `${operand(a, objects)} ${event.data.operator} ${operand(b, objects)} = ${result}`;
    }
    case 'CALL':
      return `Call ${event.data.frame.methodId}`;
    case 'RETURN':
      return event.data.result === null ? 'Return' : `Return ${formatValue(event.data.result, objects)}`;
    case 'UNCAPTURED': {
      const changed = [
        ...event.data.unknownBindings.map(id => `${bindingName(id)} unknown`),
        ...event.data.arrays.map(a => `${arrayName(a.objectId)} changed`),
        ...event.data.unknownArrays.map(id => `${arrayName(id)} unknown`),
      ];
      return `Ran without visualization${changed.length ? ` (${changed.join(', ')})` : ''}`;
    }
    case 'EXCEPTION':
      return `${event.data.exceptionType}${event.data.message ? `: ${event.data.message}` : ''}`;
  }
}
