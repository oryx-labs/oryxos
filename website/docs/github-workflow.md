# GitHub Contribution Basics

> First time collaborating on GitHub? This one's for you. After it, you'll be able to open your first PR and read the "slang" in a review.

Open source has one common workflow and one common set of "slang". They're the same on OryxOS, on the Linux kernel, on almost every GitHub project. **Learn it once, use it anywhere.** Don't be intimidated — every maintainer started out not understanding this either.

## The core flow: Fork → Branch → PR → Review → Merge

![Fork → Branch → Open a PR → CI runs · review → LGTM → Merge](/images/gh-flow-en.svg)

### 1. Fork

Click **Fork** at the top-right of the project page to copy the repo to your own account. You have full rights over your fork; breaking it doesn't affect the original.

### 2. Clone & Branch

```bash
git clone https://github.com/<your-username>/oryxos.git
cd oryxos
git checkout -b fix/typo-in-readme    # semantic branch name: type/what
```

**Never work directly on `main`.** One new branch per change; one branch per PR.

### 3. Commit and push

```bash
git add .
git commit -m "docs: fix typo in README"
git push origin fix/typo-in-readme
```

### 4. Open a Pull Request (PR)

After pushing, GitHub shows **Compare & pull request**. Click it and fill in:

- **Title**: one line saying what you did.
- **Body**: why, and how you verified it; link issues with `Closes #123` (auto-closes that issue on merge).

A good PR = **small, focused, well-described, tested.** Don't cram ten things into one PR — nobody can review that.

### 5. Review

A maintainer will look at your PR and may:

- **Approve** — good to merge.
- **Request changes** — please fix, with specific comments.
- **Comment** — a thought, without blocking the merge.

**Being asked to change things is normal — it's not a rejection.** This is the fastest way to learn in open source. Address the feedback, push to the same branch, and the PR updates automatically.

### 6. Merge

With green CI and enough Approvals, a maintainer merges it. Your name is now in the project's history. 🎉

## Review slang cheat sheet

You'll see these abbreviations in reviews. They aren't showing off — they're efficient shorthand the community has built up over years:

| Term | Meaning |
|------|---------|
| **LGTM** | *Looks Good To Me* — the most common approval signal |
| **SGTM** | *Sounds Good To Me* — I'm fine with the approach |
| **PTAL** | *Please Take A Look* (often to @ someone to review) |
| **TAL** | *Take A Look* |
| **nit** | *nitpick* — a minor comment; not blocking, but nicer if fixed |
| **WIP** | *Work In Progress* — not done yet, open for direction; don't review/merge yet |
| **RFC** | *Request For Comments* — usually a bigger design discussion |
| **+1 / 👍** | I agree |
| **ship it** | Let's merge! |
| **ping** | A nudge ("anyone still looking at this PR?") |
| **rebase** | Please sync your branch onto the latest `main` (see below) |
| **squash** | Compress several small commits into one before merging |
| **good first issue** | An issue suited for newcomers — the best place to start |
| **breaking change** | Breaks existing usage — handle with extra care |

## Common Git operations

**Sync to the latest main (rebase)** — when your PR is behind, or has conflicts:

```bash
git remote add upstream https://github.com/oryx-labs/oryxos.git   # once
git fetch upstream
git rebase upstream/main
# resolve conflicts if any, then:
git rebase --continue
git push --force-with-lease     # rebase rewrote history, so force-push your branch
```

**Amend the last commit message:**

```bash
git commit --amend
```

> `--force-with-lease` is safer than `--force`: it only pushes if nobody else has touched your remote branch, so you won't clobber someone else's commits.

## How to write a good PR

- **Small and focused**: one thing at a time. 300 lines is ten times easier to review than 3000.
- **Explain it**: the body says "why this change" and "how I verified it" — don't make reviewers guess.
- **Bring tests**: features come with tests; both you and the reviewer sleep better.
- **Pass gates locally**: `mvn clean verify` green before you push; don't leave red lights for CI.
- **Respond to review**: reply to every comment — "done" if you fixed it, or kindly explain your reasoning if you disagree.

## How to do a good review

Want to become a [maintainer](./maintainer)? Reviewing is the path. A good review:

- **Is about the code, not the person.**
- **Is specific and actionable**: "this null isn't checked, line 42 will NPE" beats "this is badly written".
- **Weighs severity**: mark small suggestions `nit`; only request changes for real problems.
- **Encourages**: when something's done well, say so. A newcomer encouraged once is more likely to stay.
- **Approves clearly when satisfied**: click Approve, reply LGTM — don't leave the PR hanging.

## Mindset

- **Don't fear submitting something imperfect.** Put it out, get it reviewed, make it better — that *is* progress.
- **Ask when you don't know.** Questions in issues and PRs are always welcome; there are no "dumb questions".
- **Everyone started here.** The people who seem so advanced to you were once puzzling over how `git rebase` works.

Ready? Head to the [Contributing Guide](./contributing) and open your first PR. We'll be in the review thread waiting for you.
