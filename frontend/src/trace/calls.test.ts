import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { callLine, callsAt } from './calls.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

test('calls form a tree from recorded calls and returns, up to the cursor only (13a)', () => {
  const p = new Playback(fixture('methods')); // factorial(3) recursing to factorial(1), then other helpers
  const stepOf = (pred: (e: Trace['events'][number]) => boolean) => {
    const e = p.trace.events.find(pred)!;
    return 'step' in e ? e.step : -1;
  };
  const innermost = stepOf(e => e.kind === 'CALL' && e.data.frame.frameId === 'f3');
  const at = (k: number) => callsAt(p, p.at(k));

  assert.deepEqual(at(0).nodes.map(n => n.frameId), ['f0'], 'only main before any call');
  const deep = at(innermost);
  assert.deepEqual(deep.nodes.map(n => [n.frameId, n.callerFrameId]), [['f0', null], ['f1', 'f0'], ['f2', 'f1'], ['f3', 'f2']]);
  assert.ok(deep.nodes.every(n => !n.ended), 'nothing has returned yet');
  assert.equal(deep.nodes[3].callText, 'factorial(n - 1)');
  assert.deepEqual(deep.nodes[3].args.map(a => a.value), [{ kind: 'int', value: '1' }]);
  // Each running frame waits on the call it made.
  assert.equal(deep.waitingOn.get('f2')?.frameId, 'f3');
  assert.equal(deep.waitingOn.get('f0')?.frameId, 'f1');

  // No future facts: the calls made later in main are absent at this cursor.
  assert.ok(!deep.nodes.some(n => n.methodId.includes('swap')));
  const end = at(p.steps);
  assert.ok(end.nodes.some(n => n.methodId.includes('swap')) && end.nodes.every(n => n.callerFrameId === null || n.ended));
  assert.deepEqual(end.nodes.find(n => n.frameId === 'f1')?.result, { kind: 'int', value: '6' });
});

test('a returned value stays with its caller until the caller goes on, and that step uses it (13b, 13c)', () => {
  const p = new Playback(fixture('methods'));
  const step = (seq: number) => { const e = p.trace.events[seq - 1]; return 'step' in e ? e.step : -1; };
  const ret = p.trace.events.find(e => e.kind === 'RETURN' && e.frameId === 'f3')!;
  const k = step(ret.seq);
  const after = callsAt(p, p.at(k));
  assert.deepEqual(after.received.get('f2')?.map(n => n.frameId), ['f3'], 'f2 received factorial(1)');
  assert.equal(after.waitingOn.get('f2'), undefined, 'f2 no longer waits');
  // f2 goes on with n * <result>: the value is shown on that step, then it is gone.
  const uses = callsAt(p, p.at(k + 1));
  assert.equal(p.at(k + 1).event?.frameId, 'f2');
  assert.deepEqual(uses.received.get('f2')?.map(n => n.frameId), ['f3']);
  assert.equal(callsAt(p, p.at(k + 2)).received.get('f2'), undefined);
  // Back one step: f2 waits again.
  assert.equal(callsAt(p, p.at(k - 1)).waitingOn.get('f2')?.frameId, 'f3');

  const { line, at } = callLine(p.trace.source.text, after.nodes.find(n => n.frameId === 'f3')!);
  assert.equal(line, 'return n * factorial(n - 1);');
  assert.equal(line.slice(at, at + 'factorial(n - 1)'.length), 'factorial(n - 1)');
});
