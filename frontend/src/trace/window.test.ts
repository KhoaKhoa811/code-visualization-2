import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { Playback } from './playback.ts';
import { windowsAt, type SlidingWindow } from './window.ts';
import { namesOf } from './format.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

/** Every window at every cursor, as "array [lo..hi] label". */
function all(p: Playback): { k: number; text: string; w: SlidingWindow }[] {
  const out: { k: number; text: string; w: SlidingWindow }[] = [];
  for (let k = 0; k <= p.steps; k++) {
    const v = p.at(k), s = v.beforeEnd ?? v.state;
    for (const [id, ws] of windowsAt(p, k, s)) for (const w of ws) out.push({ k, text: `${namesOf(s, id)[0]} [${w.lo}..${w.hi}] ${w.label}`, w });
  }
  return out;
}

test('a fixed and a growing window over arrays, a set window and a counter window over strings (25)', () => {
  const found = all(new Playback(fixture('sliding-window')));
  const texts = new Set(found.map(f => f.text));
  // Fixed size 3: a cell enters, the oldest leaves; shown only from the first leave.
  assert.ok(texts.has('a [1..3] sum = 7') && texts.has('a [2..4] sum = 9') && texts.has('a [3..5] sum = 6'));
  assert.equal(found.find(f => f.text.startsWith('a '))!.w.left, 0, 'first shown at the step cell 0 leaves');
  // Growing and shrinking until the total drops below 7.
  assert.ok(texts.has('b [4..5] total = 7') && texts.has('b [5..5] total = 3'));
  // Characters in a set (added through the variable c), and in a counter array.
  assert.ok(texts.has('s [2..4] seen (3)'));
  assert.ok(found.some(f => f.text === 't [1..4] freq'));
  // The step's own move is marked: + for the cell that entered, − for the one that left.
  assert.ok(found.some(f => f.text.startsWith('a ') && f.w.entered === 4));
  assert.ok(found.some(f => f.text.startsWith('s ') && f.w.left === 1));
  for (const f of found) assert.ok(f.w.lo <= f.w.hi, f.text);
});

test('no other program shows a window: totals that never take a value back out are not windows (25)', () => {
  const dir = new URL('./fixtures/', import.meta.url);
  for (const name of readdirSync(dir).filter(f => f.endsWith('.json') && f !== 'sliding-window.json')) {
    let p: Playback;
    try { p = new Playback(JSON.parse(readFileSync(new URL(name, dir), 'utf8'))); } catch { continue; }
    if (!p.available) continue;
    assert.deepEqual(all(p).map(f => f.text), [], name);
  }
});
