const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Ajv2020 = require('ajv/dist/2020');

const read = file => JSON.parse(fs.readFileSync(path.join(__dirname, '../..', file), 'utf8'));
const ajv = new Ajv2020({ strict: true, allErrors: true });
ajv.addSchema(read('trace/v1/trace.schema.json'));
const request = ajv.compile(read('run/v1/request.schema.json'));
const result = ajv.compile(read('run/v1/result.schema.json'));
const trace = read('trace/v1/examples/valid/output-only.json');
const snapshot = (phase, overrides = {}) => ({
  schemaVersion: '1.1.0', runId: trace.runId, sourceSha256: trace.source.sha256, input: { mode: 'none', sha256: null },
  revision: 0, phase, terminationConfirmed: ['cleaning', 'finished'].includes(phase),
  cleanupVerified: phase === 'finished', slotHeld: !['queued', 'finished'].includes(phase),
  trace: null, infrastructureFailures: [], ...overrides
});

test('request preserves trace source shape and excludes caller execution controls', () => {
  const valid = { schemaVersion: '1.2.0', source: trace.source, inputMode: 'none' };
  assert.equal(request(valid), true);
  assert.equal(request({ ...valid, inputMode: 'live' }), true); // Part 8g: a live console may send input
  const given = { text: '3\n1 2 3\n', sha256: 'a'.repeat(64) };
  assert.equal(request({ ...valid, inputMode: 'provided', input: given }), true); // Part 10a: test input with the request
  assert.equal(request({ ...valid, inputMode: 'provided' }), false, 'provided needs its input');
  assert.equal(request({ ...valid, input: given }), false, 'only provided carries input');
  assert.equal(request({ ...valid, inputMode: 'provided', input: { text: 'x' } }), false, 'input needs its hash');
  for (const change of [{ schemaVersion: '1.0.0' }, { schemaVersion: '1.1.0' }, { schemaVersion: '2.0.0' }, { inputMode: 'tty' },
    { command: 'java Main' }, { runId: 'caller' }, { image: 'caller-image' },
    { source: { ...trace.source, file: '../Main.java' } }]) {
    assert.equal(request({ ...valid, ...change }), false, JSON.stringify(change));
  }
});

test('normal phase snapshots validate without claiming a transition implementation', () => {
  for (const phase of ['queued', 'preparing', 'compiling', 'executing', 'stopping', 'cleaning', 'finished']) {
    assert.equal(result(snapshot(phase)), true, JSON.stringify(result.errors));
  }
  assert.equal(result(snapshot('stopping', { slotHeld: false })), true);
});

test('unconfirmed termination cannot publish trace or release an occupied phase slot', () => {
  assert.equal(result(snapshot('stopping', { infrastructureFailures: ['TERMINATION_UNCONFIRMED'] })), true);
  for (const phase of ['queued', 'preparing', 'compiling', 'executing', 'stopping']) {
    assert.equal(result(snapshot(phase, { trace })), false, phase);
    assert.equal(result(snapshot(phase, { terminationConfirmed: true })), false, phase);
  }
  for (const phase of ['preparing', 'compiling', 'executing', 'cleaning']) {
    assert.equal(result(snapshot(phase, { slotHeld: false })), false, phase);
  }
});

test('cleanup failure preserves valid terminal trace and retained slot; recovery retains failure', () => {
  const failures = ['TERMINATION_UNCONFIRMED', 'CLEANUP_UNCONFIRMED'];
  assert.equal(result(snapshot('cleaning', { trace, infrastructureFailures: failures })), true);
  assert.equal(result(snapshot('finished', { trace, infrastructureFailures: failures })), true);
  assert.equal(result(snapshot('cleaning', { cleanupVerified: true })), false);
  for (const change of [{ terminationConfirmed: false }, { cleanupVerified: false }, { slotHeld: true }]) {
    assert.equal(result(snapshot('finished', change)), false);
  }
});

test('infrastructure-only completion does not invent Java status; nested trace remains v1', () => {
  assert.equal(result(snapshot('finished', { infrastructureFailures: ['SETUP_FAILED'] })), true);
  assert.equal(result(snapshot('finished', { trace: { ...trace, execution: { status: 'infrastructure_failure', reason: 'bad' } } })), false);
  assert.equal(result(snapshot('finished', { input: { mode: 'provided', sha256: 'b'.repeat(64) } })), true); // Part 10a
  for (const change of [{ revision: -1 }, { schemaVersion: '2.0.0' }, { schemaVersion: '1.0.0' }, { hostPath: 'C:/secret' },
    { input: { mode: 'tty', sha256: null } }, { input: { mode: 'none' } }, { input: undefined },
    { infrastructureFailures: ['UNKNOWN'] }, { infrastructureFailures: ['SETUP_FAILED', 'SETUP_FAILED'] }]) {
    assert.equal(result(snapshot('finished', change)), false);
  }
});

test('shared snapshot checker adds trace checks and run/source identity', () => {
  const { problems } = require('../check.cjs');
  assert.deepEqual(problems(snapshot('finished', { trace })), []);
  assert.deepEqual(problems(snapshot('finished', { trace, runId: 'run-other' })), ['run-id']);
  assert.deepEqual(problems(snapshot('finished', { trace, sourceSha256: '0'.repeat(64) })), ['source-identity']);
  assert.match(problems(snapshot('finished', { slotHeld: true }))[0], /^schema:/);
});
