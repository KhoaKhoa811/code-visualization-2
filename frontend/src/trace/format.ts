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
    case 'function': case 'stream': case 'optional': return value.label; // 1.10.0
    case 'ref': {
      const object = objects?.get(value.objectId);
      if (object?.kind === 'string') return JSON.stringify(stringText(object));
      if (object?.kind === 'array') return arrayType(object);
      if (object?.kind === 'object') return objectLabel(object);
      if (object?.kind === 'builder') return `StringBuilder ${JSON.stringify(String.fromCharCode(...object.utf16))}`;
      if (object?.kind === 'collection') return `${shortName(object.className)}(${object.elements.length})`;
      if (object?.kind === 'map') return `${shortName(object.className)}(${object.entries.length})`;
      if (object?.kind === 'input') return `${shortName(object.className)}(System.in)`;
      if (object?.kind === 'entry') return `${formatValue(object.key, objects)}=${formatValue(object.value, objects)}`; // 1.9.0
      if (object?.kind === 'exception') return `${exceptionName(object.className)}(${object.message === null ? '' : JSON.stringify(object.message)})`;
      return `ref ${value.objectId}`;
    }
  }
}

/** An object of a traced class: its short class name and identity, such as Node o3. */
export function objectLabel(object: TraceObject & { kind: 'object' }): string {
  return `${shortClass(object.className)} ${object.objectId}`;
}

/** java.util.ArrayList shows as ArrayList. */
export function shortName(className: string): string {
  return className.slice(className.lastIndexOf('.') + 1);
}

/** 1.7.0: java.lang.ArithmeticException shows as ArithmeticException, Main$InsufficientFunds as InsufficientFunds. */
export function exceptionName(className: string): string {
  const simple = className.slice(className.lastIndexOf('.') + 1);
  return simple.startsWith('Main$') ? simple.slice(5).replaceAll('$', '.') : simple.replaceAll('$', '.');
}

/** Main.Node shows as Node. */
export function shortClass(name: string): string {
  return name.startsWith('Main.') ? name.slice(5) : name;
}

/** Java notation with the length in the first dimension: int[3], int[2][] for rows of int[]. */
export function arrayType(array: TraceObject & { kind: 'array' }): string {
  const t = array.elementType;
  const nested = t.indexOf('[');
  const base = nested < 0 ? t : t.slice(0, nested);
  return `${base.replace('java.lang.', '')}[${array.elements.length}]${nested < 0 ? '' : t.slice(nested)}`;
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

/**
 * Names that currently reach an object: bindings first (aliases share it), then element
 * paths through arrays such as g[1] for a row of g.
 */
export function namesOf(state: State, objectId: string, visiting: Set<string> = new Set()): string[] {
  const direct = state.bindings.filter(b => b.value.kind === 'ref' && b.value.objectId === objectId).map(b => b.name);
  if (visiting.has(objectId) || visiting.size >= 4) return direct;
  visiting.add(objectId);
  const paths: string[] = [];
  for (const parent of state.objects.values()) {
    if (parent.kind === 'array') {
      parent.elements.forEach((element, index) => {
        if (element.kind === 'ref' && element.objectId === objectId) {
          for (const name of namesOf(state, parent.objectId, visiting)) paths.push(`${name}[${index}]`);
        }
      });
    } else if (parent.kind === 'collection') { // 1.5.0: an element of a library collection
      parent.elements.forEach((element, index) => {
        if (element.kind === 'ref' && element.objectId === objectId) {
          for (const name of namesOf(state, parent.objectId, visiting)) paths.push(`${name}[${index}]`);
        }
      });
    } else if (parent.kind === 'map') { // 1.5.0: a value of a map, by its key
      for (const entry of parent.entries) {
        if (entry.value.kind === 'ref' && entry.value.objectId === objectId) {
          for (const name of namesOf(state, parent.objectId, visiting)) paths.push(`${name}[${formatValue(entry.key, state.objects)}]`);
        }
      }
    } else if (parent.kind === 'object') { // 1.4.0: field paths such as head.next
      for (const field of parent.fields) {
        if (field.value.kind === 'ref' && field.value.objectId === objectId) {
          for (const name of namesOf(state, parent.objectId, visiting)) paths.push(`${name}.${field.name}`);
        }
      }
    }
  }
  visiting.delete(objectId);
  return [...direct, ...paths];
}

function operand(o: Operand, objects: Map<string, TraceObject>): string {
  return 'skipped' in o ? '(not evaluated)' : formatValue(o.value, objects);
}

/**
 * One-line description of the kth operation, read from the state at that cursor. With the source
 * text, an exception raised by a throw statement reads as a throw (1.7.0).
 */
export function describe(event: ObservableEvent, state: State, source?: string): string {
  const objects = state.objects;
  const arrayName = (objectId: string) => namesOf(state, objectId)[0] ?? 'array';
  const objectName = (objectId: string) => {
    const object = objects.get(objectId);
    return namesOf(state, objectId)[0] ?? (object?.kind === 'object' ? objectLabel(object) : object ? formatValue({ kind: 'ref', objectId }, objects) : 'object');
  };
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
      if (o.kind === 'array') return `Create ${arrayType(o)}`;
      if (o.kind === 'object') return `Create ${objectLabel(o)}`;
      if (o.kind === 'string') return 'Create string';
      if (o.kind === 'input') return `Create ${shortName(o.className)}(System.in)`;
      if (o.kind === 'entry') return 'Create map entry';
      return `Create ${shortName(o.className)}`;
    }
    case 'LIBRARY': {
      // java.util.List.add(java.lang.Object) on xs reads as xs.add(5) → true
      const method = event.data.method;
      // 1.9.0: one element of a for-each over a collection or a map view
      if (method === 'java.util.Iterator.next()' && event.data.objectId && event.data.result) {
        const what = event.data.view ? { keys: 'key', values: 'value', entries: 'entry' }[event.data.view] : 'element';
        return `Next ${what} of ${objectName(event.data.objectId)}: ${formatValue(event.data.result, objects)}`;
      }
      const open = method.indexOf('(');
      const name = method.slice(method.lastIndexOf('.', open) + 1, open);
      const owner = method.slice(0, method.lastIndexOf('.', open));
      const target = event.data.objectId ? objectName(event.data.objectId) : shortName(owner);
      // 10d: an array or collection argument held by a variable reads as its name (Arrays.sort(a)).
      const args = event.data.arguments.map(a => a.kind === 'ref' && objects.get(a.objectId)?.kind !== 'string' && namesOf(state, a.objectId)[0]
        || formatValue(a, objects)).join(', ');
      const call = `${target}.${name}(${args})`;
      const text = event.data.result ? `${call} → ${formatValue(event.data.result, objects)}` : call;
      // 1.6.0: a Scanner read consumed delivered console input.
      const input = event.data.objectId !== null && objects.get(event.data.objectId)?.kind === 'input';
      return input && name !== 'close' ? `Read input: ${text}` : text;
    }
    case 'FIELD_READ':
      return `Read ${objectName(event.data.objectId)}.${event.data.field} = ${formatValue(event.data.value, objects)}`;
    case 'FIELD_WRITE':
      return `Write ${objectName(event.data.objectId)}.${event.data.field}: ${formatValue(event.data.previous, objects)} → ${formatValue(event.data.value, objects)}`;
    case 'SWITCH': {
      const value = formatValue(event.data.value);
      if (event.data.matched === 'case') return `Switch on ${value}: matching case`;
      if (event.data.matched === 'default') return `Switch on ${value}: no case matches, default`;
      return `Switch on ${value}: no case matches`;
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
      if (event.data.operands.length > 2) { // a flattened string concatenation (1.5.0)
        return `${event.data.operands.map(o => operand(o, objects)).join(` ${event.data.operator} `)} = ${result}`;
      }
      return `${operand(a, objects)} ${event.data.operator} ${operand(b, objects)} = ${result}`;
    }
    case 'CALL':
      // 1.10.0: a function a library call runs reads as called via that call (Call lambda at 5:20 via forEach(x = 1))
      return `Call ${methodName(event.data.frame.methodId)}${event.data.frame.via ? ` via ${methodName(event.data.frame.via)}` : ''}(${event.data.parameters.map(p => `${p.name} = ${formatValue(p.value, objects)}`).join(', ')})`;
    case 'RETURN':
      if (event.data.result !== null) return `Return ${formatValue(event.data.result, objects)}`;
      return event.data.implicit ? 'Return (end of method)' : 'Return';
    case 'UNCAPTURED': {
      const changed = [
        ...event.data.unknownBindings.map(id => `${bindingName(id)} unknown`),
        ...event.data.arrays.map(a => `${arrayName(a.objectId)} changed`),
        ...event.data.unknownArrays.map(id => `${arrayName(id)} unknown`),
        ...event.data.objects.map(o => `${objectName(o.objectId)} changed`),
        ...event.data.unknownObjects.map(id => `${objectName(id)} unknown`),
        ...event.data.collections.map(o => `${objectName(o.objectId)} changed`),
        ...event.data.unknownCollections.map(id => `${objectName(id)} unknown`),
      ];
      return `Ran without visualization${changed.length ? ` (${changed.join(', ')})` : ''}`;
    }
    case 'EXCEPTION': {
      const message = event.data.message ? `: ${event.data.message}` : '';
      if (source?.startsWith('throw', event.source.start)) return `Throw ${exceptionName(event.data.exceptionType)}${message}`;
      return `${event.data.exceptionType}${message}`;
    }
    case 'CATCH': {
      const b = event.data.binding;
      const caught = b.value.kind === 'ref' ? objects.get(b.value.objectId) : undefined;
      const what = caught?.kind === 'exception' ? `${exceptionName(caught.className)}${caught.message ? `: ${caught.message}` : ''}` : '';
      return `Catch ${b.name}${what ? ` = ${what}` : ''}`;
    }
  }
}

/** Short method name from a method id such as Main.factorial(int):int. */
export function methodName(methodId: string): string {
  const open = methodId.indexOf('(');
  const head = open < 0 ? methodId : methodId.slice(0, open);
  const name = head.slice(head.lastIndexOf('.') + 1);
  if (name.startsWith('lambda@')) return `lambda at ${name.slice(7)}`; // 1.10.0: Main.lambda@line:column(...)
  if (name !== '<init>') return name;
  const owner = head.slice(0, head.lastIndexOf('.'));
  return `new ${owner.slice(owner.lastIndexOf('.') + 1)}`; // a constructor
}
