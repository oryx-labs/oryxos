# Contributing Guide

> Welcome. Yes, you.

OryxOS is **our** project, and it's just getting started. There aren't many rules here, and you don't need to be an expert first. There's really only one thing that matters: **you're willing to get moving.** The moment you open a PR, review a diff, or fix a typo, you're already a contributor.

We want to do three things together through this project:

1. **Get moving, learn AI programming, step into open source.** A lot of people want to contribute to open source but never take the first step. This is the place to take it.
2. **Learn to become a maintainer.** Not just committing code — reviewing others' code, helping people, guarding quality, mentoring newcomers. Those skills are worth more than writing code.
3. **Put it on your résumé.** We genuinely hope this project grows. When it does, having contributed to and maintained it is a real, concrete line on your résumé.

So don't hesitate, and don't worry that your work isn't good enough. **Put it out there — we'll improve it together.**

## What you can contribute

Writing core code isn't the only kind of contribution. All of these are equally welcome:

- **Code** — fix bugs, add features, write a built-in Tool, wire up a new MCP server, improve performance.
- **Docs** — fill gaps, fix typos, make an unclear section clear, translate between English and Chinese.
- **Tests** — add cases, reproduce and report bugs, raise coverage.
- **Agent / Skill examples** — write an interesting Agent directory or Skill and add it to the examples so others can learn from it.
- **Issues** — report a bug, propose an idea, join a discussion. Even "I didn't understand this part" is valuable feedback.
- **Reviews** — go look at someone else's PR, leave a comment, drop an LGTM. This is the first step toward becoming a maintainer.

> Not sure where to start? Look for issues labeled `good first issue` in [Issues](https://github.com/oryx-labs/oryxos/issues), or just open an issue saying "I'd like to help — where do I start?"

## Use AI — but own the result

**You're welcome, and in fact strongly encouraged, to use AI throughout your contribution.** Writing code, docs, tests, debugging, understanding an unfamiliar module — go ahead and use Claude Code, Copilot, Cursor, or whatever fits your hand. OryxOS is itself a "get things done together with AI" project, and helping you step into AI programming is one of our goals.

But there's one line, and it's the same as Linus's view on AI:

> **AI is just a tool. What actually matters is that you can own the result.**

That means every PR you submit — whether or not AI wrote it — is **your** PR:

- **You understand it.** Don't submit code you can't follow yourself. After the AI generates it, you should be able to explain why it's written that way.
- **You verified it.** `mvn clean verify` green locally, the feature actually works — not "the AI said it works".
- **You own it.** Review feedback comes to you; regression bugs are yours. "The AI wrote it" is not an excuse.

In short: **AI makes you faster and stronger, but the name and the responsibility are yours.** Use it well — then vouch for it as if you'd hand-written every line.

## Setting up

OryxOS is a Java 21 + Spring Boot Maven multi-module project; the admin console frontend is Vue. You'll need:

- **JDK 21+** (required — virtual threads are core to the runtime)
- **Maven 3.9+**
- **Node.js 18+** (only if you're touching the `oryxos-web` admin console frontend)

```bash
# clone your fork
git clone https://github.com/<your-username>/oryxos.git
cd oryxos

# build + run all quality gates and tests
mvn clean verify

# run locally (configure a Provider first, per Quick Start)
java -jar oryxos-boot/target/oryxos-boot-*.jar serve
```

See [Quick Start](./quick-start) for full build and configuration details.

## Contribution flow (standard GitHub flow)

If you're new to open source, this flow is the same as on almost every GitHub project — learn it once, use it forever. New to it? Read [GitHub Contribution Basics](./github-workflow) first.

1. **Fork** this repo to your own account.
2. **Branch** off `main` with a semantic name.

   ```bash
   git checkout -b feat/add-slack-channel
   ```

3. **Write code** in small, focused steps. One PR does one thing — easy to review, easy to merge.
4. **Test locally**: `mvn clean verify` must be **green** before you push (see Quality Gates below).
5. **Commit** with a clear message (see below).
6. **Push and open a PR**: the title says what you did; the body says why and how to verify. Link related issues (`Closes #123`).
7. **Engage in review**: a maintainer will look at your PR. Address the feedback together — this is the fastest way to learn, so relax.
8. **Merge**: with an LGTM/Approve and green CI, a maintainer merges it. 🎉

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/) style so history reads at a glance:

```
<type>(<optional scope>): <short description>

feat(tool): add http_download built-in tool
fix(web): auth cookie missing Secure flag on HTTPS requests
docs: add contributing guide
chore: bump version to 0.1.1
```

Common types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`.

**PR titles** should be just as clear. One convention: **a PR whose title starts with `release:` and is merged to `main` triggers an automatic release** (creates a Release + uploads the tarball), so don't use that prefix on regular PRs.

## Quality gates (please read)

OryxOS gates on `mvn verify`. Pass it locally before you push — don't leave red lights for CI and reviewers:

- **Spotless** (google-java-format) — formatting. Failing? Run `mvn spotless:apply` to auto-fix.
- **P3C / PMD** — Alibaba Java coding rules (e.g. string literals must be extracted to constants).
- **Checkstyle** — e.g. unused imports are hard errors.
- **SpotBugs + FindSecBugs** — potential bugs and security issues.
- **Unit tests** — JUnit; please ship tests with new features.

OryxOS also has a few **architectural principles** (self-implemented ReAct loop; Spring AI only for protocol conversion + `@Tool` schema; explicit Provider mapping; audit tables written from day one; sandbox whitelists; synchronous + virtual threads…). When touching the core, check the constitution in `CLAUDE.md`; if unsure, ask in an issue/PR rather than guessing alone.

## Code of conduct

In one line: **be kind to people, be serious about the work.**

We're just starting out. The thing we fear most isn't code that isn't good enough — it's someone who won't take part because they're afraid to look foolish. So:

- Review the code, not the person; be specific and actionable, not just "this is wrong".
- Asking questions isn't embarrassing. Every maintainer started at "what is this?".
- Welcome newcomers. One more word of encouragement means one more person who stays.

## Reach us

- **Issues**: report bugs, propose ideas → [github.com/oryx-labs/oryxos/issues](https://github.com/oryx-labs/oryxos/issues)
- **Pull Requests**: start contributing → [github.com/oryx-labs/oryxos/pulls](https://github.com/oryx-labs/oryxos/pulls)

Want to go further and become a maintainer? See the [Maintainer Guide](./maintainer). First time collaborating on GitHub? See [GitHub Contribution Basics](./github-workflow).
