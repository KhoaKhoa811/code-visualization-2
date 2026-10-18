// Run-v1 snapshot checks: strict result-schema validation, the shared trace-v1
// checks for a nested trace, and source/run identity between snapshot and trace.
// Does not prove transition history, slot accounting or timing.
// CLI: node run/check.cjs <snapshot.json>... prints problems, exits 1 if any.
const fs = require('node:fs');
const path = require('node:path');
const traceCheck = require('../trace/check.cjs');

const result = traceCheck.ajv.compile(
  JSON.parse(fs.readFileSync(path.join(__dirname, 'v1', 'result.schema.json'), 'utf8')));

function problems(snapshot) {
  if (!result(snapshot)) return [`schema: ${traceCheck.ajv.errorsText(result.errors)}`];
  if (snapshot.trace === null) return [];
  const errors = traceCheck.metadataErrors(snapshot.trace);
  if (snapshot.trace.runId !== snapshot.runId) errors.push('run-id');
  if (snapshot.trace.source.sha256 !== snapshot.sourceSha256) errors.push('source-identity');
  return errors;
}

module.exports = { problems };

if (require.main === module) {
  let failed = false;
  for (const file of process.argv.slice(2)) {
    const found = problems(JSON.parse(fs.readFileSync(file, 'utf8')));
    if (found.length) { failed = true; console.log(`${file}: ${found.join('; ')}`); }
  }
  process.exit(failed ? 1 : 0);
}
