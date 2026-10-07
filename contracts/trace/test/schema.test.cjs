const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const Ajv2020 = require('ajv/dist/2020');

const root = path.resolve(__dirname, '../v1');
const read = file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8'));
const schema = read('trace.schema.json');
const ajv = new Ajv2020({ strict: true, allErrors: true });
const validate = ajv.compile(schema);
const validateValue = ajv.getSchema(`${schema.$id}#/$defs/value`);
const cases = read('examples/manifest.json');

// Deliberately limited to envelope/source/boundary checks. This is not a
// production semantic validator, Java interpreter, or playback reducer.
function metadataErrors(trace) {
  const errors = [];
  const hash = createHash('sha256').update(trace.source.text, 'utf8').digest('hex');
  if (hash !== trace.source.sha256) errors.push('source-hash');
  if (!trace.source.text.isWellFormed()) errors.push('source-unicode');
  if (trace.capture.safeEventCount !== trace.events.length) errors.push('safe-count');
  const operations = trace.events.filter(event => 'step' in event);
  trace.events.forEach((event, index) => {
    if (event.seq !== index + 1) errors.push('sequence');
    if (event.source && (event.source.start > event.source.end ||
        event.source.end > trace.source.text.length)) errors.push('source-range');
  });
  if (operations.length !== trace.stepEnds.length) errors.push('step-count');
  operations.forEach((event, index) => {
    if (event.step !== index + 1) errors.push('step-number');
    const expected = index === operations.length - 1 && trace.capture.status === 'complete'
      ? trace.events.length : event.seq;
    if (trace.stepEnds[index] !== expected) errors.push('step-boundary');
  });
  if (trace.capture.status === 'partial' && operations.length === 0 && trace.events.length)
    errors.push('partial-empty-prefix');
  return errors;
}

test('schema declares and validates against the 2020-12 meta-schema', () => {
  assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema');
  assert.equal(ajv.validateSchema(schema), true);
});

test('manifest covers exactly the checked-in trace fixtures', () => {
  const files = ['valid', 'invalid', 'semantic-invalid'].flatMap(directory =>
    fs.readdirSync(path.join(root, 'examples', directory)).map(name => `${directory}/${name}`));
  assert.deepEqual(cases.map(entry => entry.file).sort(), files.sort());
  assert.equal(new Set(cases.map(entry => entry.file)).size, cases.length);
});

for (const entry of cases) {
  test(`trace schema: ${entry.file}`, () => {
    const trace = read(`examples/${entry.file}`);
    const untouched = JSON.stringify(trace);
    const actual = validate(trace);
    assert.equal(actual, entry.schemaValid, `${entry.reason}\n${ajv.errorsText(validate.errors)}`);
    assert.equal(JSON.stringify(trace), untouched, 'Validation must not coerce or mutate facts');
    if (!entry.schemaValid) {
      assert.ok(validate.errors.some(error => error.keyword === entry.keyword &&
        error.instancePath === entry.instancePath), 'Expected targeted validation failure');
    }
    if (entry.file.startsWith('valid/')) assert.deepEqual(metadataErrors(trace), []);
  });
}

for (const entry of read('examples/values.json')) {
  test(`typed value: ${entry.name}`, () => {
    assert.equal(validateValue(entry.value), entry.valid, ajv.errorsText(validateValue.errors));
  });
}

test('metadata checks expose schema-valid hash, sequence, and future-boundary errors', () => {
  for (const [file, reason] of [
    ['wrong-hash', 'source-hash'], ['sequence-gap', 'sequence'], ['future-boundary', 'step-boundary']
  ]) assert.ok(metadataErrors(read(`examples/semantic-invalid/${file}.json`)).includes(reason));
});

test('each declared event variant has a positive fixture', () => {
  const kinds = new Set(cases.filter(entry => entry.file.startsWith('valid/'))
    .flatMap(entry => read(`examples/${entry.file}`).events.map(event => event.kind)));
  const variants = schema.$defs.event.oneOf.map(entry => entry.$ref.split('/').at(-1));
  assert.deepEqual([...kinds].sort(), variants.sort());
});

test('numeric representation preserves large integer and raw floating-point bits', () => {
  const values = read('examples/values.json');
  const find = name => values.find(entry => entry.name === name).value;
  assert.equal(BigInt(find('long-max').value), (1n << 63n) - 1n);
  assert.equal(BigInt(find('long-min').value), -(1n << 63n));
  const bits = find('double-negative-zero').bits;
  assert.equal(Object.is(Buffer.from(bits, 'hex').readDoubleBE(), -0), true);
  assert.equal(Buffer.from(find('double-positive-infinity').bits, 'hex').readDoubleBE(), Infinity);
  assert.ok(Number.isNaN(Buffer.from(find('double-nan-payload').bits, 'hex').readDoubleBE()));
  assert.equal(JSON.parse(JSON.stringify(find('double-nan-payload'))).bits, '7ff8000000000001');
});
