const test = require('node:test');
const assert = require('node:assert/strict');
const { validatePullRequest } = require('./check-pr-contract.cjs');

const completeBody = `
## Summary
Change summary.
## Related issue
Closes #123
## Changes
Implementation details.
## Verification
Tests passed.
## Documentation
N/A: no docs change.
## Compatibility / migration
N/A: no behavior change.
## Risk
Low.
`;

test('accepts a conventional title and all required sections', () => {
  assert.deepEqual(validatePullRequest('fix(web): handle expired session', completeBody), []);
});

test('accepts a release title exception', () => {
  assert.deepEqual(validatePullRequest('release: prepare v0.2.0', completeBody), []);
});

test('rejects an unrecognized title format', () => {
  const errors = validatePullRequest('Update the web session behavior', completeBody);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /Conventional Commit format/);
});

test('reports missing required PR sections', () => {
  const errors = validatePullRequest('docs: clarify setup', '## Summary\nUpdated setup text.');
  assert.equal(errors.length, 6);
  assert.match(errors[0], /Related issue/);
});

test('does not treat template comments as completed section content', () => {
  const body = `## Summary\n<!-- What changed? -->\n## Related issue\n<!-- Link an issue -->`;
  const errors = validatePullRequest('docs: clarify setup', body);
  assert.equal(errors.length, 7);
  assert.match(errors[0], /Summary/);
});
