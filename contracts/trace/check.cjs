// Shared trace-v1 checks: strict schema validation plus the limited envelope,
// source and cursor-boundary checks. Not a production semantic validator,
// Java interpreter or playback reducer.
// CLI: node trace/check.cjs <trace.json>... prints problems, exits 1 if any.
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const Ajv2020 = require('ajv/dist/2020');

const schema = JSON.parse(fs.readFileSync(path.join(__dirname, 'v1', 'trace.schema.json'), 'utf8'));
const ajv = new Ajv2020({ strict: true, allErrors: true });
const validate = ajv.compile(schema);

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

function problems(trace) {
  if (!validate(trace)) return [`schema: ${ajv.errorsText(validate.errors)}`];
  return metadataErrors(trace);
}

module.exports = { schema, ajv, validate, metadataErrors, problems };

if (require.main === module) {
  let failed = false;
  for (const file of process.argv.slice(2)) {
    const found = problems(JSON.parse(fs.readFileSync(file, 'utf8')));
    if (found.length) { failed = true; console.log(`${file}: ${found.join('; ')}`); }
  }
  process.exit(failed ? 1 : 0);
}
