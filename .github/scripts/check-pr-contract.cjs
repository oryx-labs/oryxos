#!/usr/bin/env node

const TITLE_PATTERN = /^(feat|fix|docs|test|refactor|chore|ci|build|perf|style|revert)(\([a-z0-9][a-z0-9._/-]*\))?: .+$/i;
const RELEASE_TITLE_PATTERN = /^release: .+$/i;
const REQUIRED_SECTIONS = [
  'Summary',
  'Related issue',
  'Changes',
  'Verification',
  'Documentation',
  'Compatibility / migration',
  'Risk',
];

function validatePullRequest(title, body) {
  const errors = [];
  const normalizedTitle = (title || '').trim();

  if (!TITLE_PATTERN.test(normalizedTitle) && !RELEASE_TITLE_PATTERN.test(normalizedTitle)) {
    errors.push('Title must use Conventional Commit format (for example, "fix(web): handle expired session") or the maintainer-approved "release: ..." form.');
  }

  const sections = new Map();
  let currentSection;
  const bodyWithoutComments = (body || '').replace(/<!--[\s\S]*?-->/g, '');

  for (const line of bodyWithoutComments.split(/\r?\n/)) {
    const heading = line.match(/^##\s+(.+?)\s*#*$/);
    if (heading) {
      currentSection = heading[1].trim().toLowerCase();
      sections.set(currentSection, []);
    } else if (currentSection) {
      sections.get(currentSection).push(line);
    }
  }

  for (const section of REQUIRED_SECTIONS) {
    const content = sections.get(section.toLowerCase()) || [];
    if (content.join('\n').trim().length === 0) {
      errors.push(`PR description is missing content in the "## ${section}" section.`);
    }
  }

  return errors;
}

if (require.main === module) {
  const errors = validatePullRequest(process.env.PR_TITLE, process.env.PR_BODY);
  const advisory = process.argv.includes('--advisory');

  if (errors.length === 0) {
    console.log('Pull request title and required sections are present.');
    process.exit(0);
  }

  for (const error of errors) {
    if (advisory) {
      console.log(`::warning::${error}`);
    } else {
      console.error(error);
    }
  }

  process.exit(advisory ? 0 : 1);
}

module.exports = { validatePullRequest };
