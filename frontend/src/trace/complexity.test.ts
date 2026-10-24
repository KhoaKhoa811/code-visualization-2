import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { byGrowth, measure, rows, shown } from './complexity.ts';
import type { ComplexityEstimate, Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const est = (name: string, base: number, poly: string, log: number, approximate = false): ComplexityEstimate =>
  ({ methodId: `Main.${name}()`, name, bound: 'O(?)', growth: { exponentialBase: base, polynomial: poly, logarithmic: log }, approximate, reasons: [], source: { start: 0, end: 1 } });

test('estimates rank from slowest-growing to fastest-growing (15b)', () => {
  const all = [est('fib', 2, '0', 0), est('sort', 0, '1', 1), est('get', 0, '0', 0), est('bubble', 0, '2', 0), est('search', 0, '0', 1, true), est('sum', 0, '1', 0)];
  assert.deepEqual([...all].sort(byGrowth).map(e => e.name), ['get', 'search', 'sum', 'sort', 'bubble', 'fib']);
  assert.equal(shown(est('search', 0, '0', 1, true)), '≈ O(?)');
});

test('measured calls and steps per method cover the whole run, nested calls included once (15b)', () => {
  const p = new Playback(fixture('methods')); // factorial(3) recursing twice, then other helpers, factorial(1)
  const m = measure(p);
  const fact = m.get('Main.factorial(int):int')!;
  assert.equal(fact.calls, 4, 'factorial(3), (2), (1), and factorial(1) later');
  const main = m.get('Main.main(java.lang.String[]):void')!;
  assert.equal(main.calls, 1);
  assert.equal(main.steps, p.steps, 'every step runs under main');
  assert.ok(fact.own < fact.steps || fact.steps === fact.own);
  const swap = m.get('Main.swap(int[],int,int):void')!;
  assert.equal(swap.calls, 1);
  const table = rows(p, [est('nothing', 0, '0', 0), { ...est('factorial', 0, '1', 0), methodId: 'Main.factorial(int):int' }]);
  assert.deepEqual(table.map(r => [r.estimate.name, r.measured?.calls ?? null]), [['nothing', null], ['factorial', 4]]);
});