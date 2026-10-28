# AI-Assisted Contributions

AI tools may help prepare issues, discuss proposals, implement agreed work, and draft pull requests. The issue remains the source of truth for the proposed behavior and decisions.

## Drafting an issue

1. Choose the issue form that matches the request: bug, feature, documentation, proposal, task, or question.
2. Draft a title with the form's prefix and fill each required field using only information provided or verified by the contributor.
3. Put open design questions in the issue so the discussion and decision stay together.
4. Ask the contributor for missing facts or mark them as unknown. Never invent reproduction steps, environment details, user impact, scope, or acceptance criteria.

## Implementing an agreed issue

1. Read the issue and its comments. Confirm that the intended behavior and acceptance criteria are clear and that the discussion has reached an explicit decision. If not, ask for clarification instead of guessing.
2. Make a focused change that implements the agreed scope. Do not add unrelated improvements.
3. Inspect the actual diff and run the relevant tests or checks when possible.
4. Draft the PR using `.github/PULL_REQUEST_TEMPLATE.md`. Link the issue with `Closes #123` when the PR completes it.
5. Report only commands and results actually observed. Distinguish passed checks from checks not run or still failing.
6. Use a Conventional Commit-style PR title such as `fix(web): handle expired session`. A maintainer-approved release PR may use `release: prepare vX.Y.Z`; the release workflow itself is triggered by a matching version tag, not by the title.

The contributor reviews and owns the final issue, code, and PR. AI output is a draft, not evidence of approval or a substitute for human review. Do not send credentials or other sensitive data to AI tools or include them in public issues and PRs.
