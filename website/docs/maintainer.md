# Maintainer Guide

> Becoming a maintainer isn't an exam. It's a beginning.

A lot of projects make "maintainer" sound mysterious — as if you need to have written tens of thousands of lines and put in years before you qualify. **OryxOS is not like that.** We're just starting out, and we need people to help hold it up. The bar here is one sentence:

> **If you want to get moving, you can become a committer.**

We genuinely hope you don't just send one or two PRs and leave, but stay and learn how to review others' code, guard quality, and mentor newcomers — **that's what makes a maintainer truly valuable, and it's what goes on your résumé.**

## The role ladder

We borrow the community-standard three roles, but keep the bar as low as possible:

| Role | What you can do | How you get here |
|------|-----------------|------------------|
| **Contributor** | File issues, open PRs, join discussions | The moment you open your first PR |
| **Committer** | Review rights — Approve others' PRs, take part in decisions | Sustained contribution + start reviewing others' PRs; nominated by a maintainer |
| **Maintainer** | Merge rights, own an area, mentor newcomers, help with releases | Steady contribution + steady reviews + ownership of a module |

Note: **these levels aren't earned by putting in time — they're earned by getting moving.** The more you review, help others, and pick up issues, the faster you rise.

## How to become a Committer

We deliberately keep this step light. No KPIs, no approval that drags on for months. Roughly:

1. **Get a few PRs merged** — quantity isn't the point; what matters is showing you understand the project and take the code seriously.
2. **Start reviewing others' PRs** — even just reading a PR carefully, leaving one specific comment, dropping an LGTM. **This step matters most** — it shows you're shifting from "someone who writes code" to "someone who maintains the project".
3. **Get nominated** — an existing maintainer sees you consistently moving and helping, and nominates you as a committer. You can also just open an issue saying "I'd like to get more involved — can I get committer rights?" We welcome initiative.

That's it. We'd rather keep the bar low and get more people moving than keep enthusiastic people out.

## How to become a Maintainer

One step past committer. It's not about how long you've been around, but about:

- **Steady contribution** — not a one-off burst, but consistently committing and solving problems.
- **Steady reviews** — your reviews have quality; people trust the Approve you give.
- **Ownership of an area** — you tend the Tool system, or the web console, or the docs site, as if it were your own.
- **Mentoring newcomers** — when someone opens their first PR, you're willing to spend time helping them get it merge-ready.

Meet these, an existing maintainer nominates you, the group agrees, and you're a maintainer.

## What maintainers do

- **Review PRs**: give specific, kind, actionable feedback; Approve / reply LGTM when satisfied.
- **Merge PRs**: after CI is green and there are enough Approvals.
- **Tend issues**: triage, label, reply, save `good first issue` for newcomers.
- **Mentor newcomers**: the most important thing. The person you help today maintains the project with you tomorrow.
- **Help with releases**: follow the release flow in [GitHub Contribution Basics](./github-workflow) (a `release:` PR title triggers automatic release).
- **Guard the foundation**: security, audit, sandbox, architectural principles — maintainers help keep the line (see `CLAUDE.md`).

## Power and responsibility

Merge rights mean you're accountable for the project's code quality. So:

- **Be worthy of your own Approve** — CI green, review done, no breaking the architectural principles.
- **Always be kind** — you are what a newcomer sees as "what this community is like". Be kind, and the community is kind.
- **When unsure, pull in another pair of eyes** — being a maintainer doesn't mean knowing everything; it means knowing when to ask for a second look.
- **Judge AI-assisted work the same way** — we welcome contributors using AI (see the [Contributing Guide](./contributing)). In review, only the result counts: is the code correct, do the tests pass, can the author own it? Don't relax the bar because "AI wrote it", and don't raise it either — there's one standard, and it's quality.

## Our promise

- We **actively** nominate and grant access — we don't leave enthusiastic people waiting.
- We treat "learning to be a maintainer" as one of the things this project gives you back — not just learning to code.
- We genuinely hope the project grows. When it does, being a **maintainer** is a heavier line on your résumé than "sent a few PRs".

Ready? Start with the [Contributing Guide](./contributing) to open your first PR, then go drop your first LGTM on someone else's. We'll walk the rest together.
