# Operating model: issue-driven development

Every change in this repository — no matter who makes it (the owner, a human
contributor, or an AI agent) — flows through the same traceable pipeline:

```
Issue  →  Branch  →  Pull Request  →  CI  →  Merge  →  Release
```

This document is the contract. It is written so that **an AI agent with zero
chat history can execute the whole process from the repository alone**, and so
the owner can drive it purely through GitHub issues. It is intentionally
identical in structure to the operating model of the sibling
[win-proxy](https://github.com/masthisis/win-proxy) repository — the flows are
kept the same on purpose.

Code-level rules (commit style, tests, docs-in-same-PR) live in
[CONTRIBUTING.md](../CONTRIBUTING.md) — this document owns the *process*.

---

## 0. Why

- **Traceability**: every line on `main` is reachable backwards — commit → PR
  → issue → original request.
- **Review surface**: CI and humans see a focused diff per change, never a
  dump on `main`.
- **Automation**: `Closes #N` auto-closes the issue on merge; tags cut
  releases automatically; release notes link back.
- **Resumability**: an interrupted session is recovered by re-reading the
  issue, not by guessing.

## 1. Direction A — agent-driven: *a request becomes a release*

Use when the owner gives a request in chat (or the backlog in
[ROADMAP.md](ROADMAP.md) is being worked off).

### Step 1 — Open the issue first

No branch is created before the issue exists. The issue carries **full
detail**, using these sections:

| Section | Content |
| --- | --- |
| **Problem** | Use case + what currently blocks it |
| **Proposed solution** | Concrete behaviour (spec), not implementation |
| **Investigation** | Files involved (real paths), constraints, risks, what already exists |
| **Acceptance criteria** | Checkable boxes; a reviewer can verify without context |
| **Agent prompt** | A copy-pasteable instruction that reproduces the whole task from the repo alone |

Labels: area (`area/core`, `area/backend`, `area/frontend`, `area/vpn`,
`area/ci`, `area/docs`), scope (`v1.0.0`, `v1.1.0`, `v1.2.0`, `v2`) and
`investigated` once the spec is complete. Reference the roadmap entry if one
exists.

### Step 2 — Branch

```
git switch -c <type>/#<issue>-<slug> main
```

`<type>` ∈ `feat | fix | docs | ci | refactor | perf | test | chore`. Example:
`feat/#2-go-core-wrapper`. One issue = one branch = one PR.

### Step 3 — Implement

- **Conventional Commits**, imperative mood, referencing the issue:
  `feat(engine): add byte-cap presets (#11)`.
- Follow [AI_DEVELOPER_GUIDE.md](AI_DEVELOPER_GUIDE.md) (ground rules,
  pitfalls P1–P12, definition of done).

### Step 4 — Pull request

- Title = conventional commit (`feat(test): speed test cancel button`).
- Body: the PR template, filled; **`Closes #11`** so the issue auto-closes on
  merge.
- CI runs automatically (unit tests, lint, APK build).

### Step 5 — CI gate + merge

- CI must be **green**; a red build is fixed on the same branch, never
  bypassed.
- **Squash merge** into `main` (one commit per change, title = PR title).
- Delete the branch after merge.

### Step 6 — Release (when the change is user-visible)

```
git switch main && git pull
git tag vX.Y.Z && git push origin vX.Y.Z
```

- **Semver**: breaking → major, feature → minor, fix → patch. A
  `-rc.N` suffix produces a pre-release.
- The Release workflow builds the signed APKs (arm64-v8a, armeabi-v7a, x86_64,
  universal), computes SHA256 checksums, and publishes the GitHub Release with
  generated notes.
- Post a comment on the issue linking the release: *shipped in vX.Y.Z*.

### Step 7 — Sync

`git pull` locally; keep `docs/CHANGELOG.md` and
[ROADMAP.md](ROADMAP.md) current in the next docs PR (or the same one).

## 2. Direction B — owner-driven: *"fetch issue #N and work on it"*

The owner can drive everything from GitHub alone:

1. Owner opens an issue — with the
   [feature template](../.github/ISSUE_TEMPLATE/feature_request.yml) or
   free-form, **pasting the full prompt verbatim** into *Proposed solution*
   if they have one.
2. Owner tells the agent one of:

   | Command | Agent behaviour |
   | --- | --- |
   | `fetch issue #N and work on it` | Re-read issue #N, post a comment with investigation + implementation plan + open questions, then execute Direction A from Step 2 with the issue already in place |
   | `fetch all open issues` | Triage: post an assessment comment on each open issue (feasible now / blocked / needs spec), group into a work order, execute top-down |
   | `...and release` | After the PR merges, also cut the semver tag and publish the release (Step 6) |

3. The agent keeps the issue alive while working: posts the plan before the
   PR, links the PR when opened, links the release when shipped. The issue is
   closed by the merge itself (`Closes #N`), never by hand.

## 3. Conventions summary

| Artifact | Rule |
| --- | --- |
| Branch | `<type>/#<issue>-<slug>` from `main` |
| Commit | Conventional Commits, imperative, `(#<issue>)` |
| PR title | Conventional commit, no issue number (body carries `Closes #N`) |
| Merge | Squash only, CI green, branch deleted |
| Version | `vMAJOR.MINOR.PATCH`, tag on `main`, semver |
| Issue | Opened **before** the branch, closed by the merge, labelled by area + scope |

## 4. Hotfixes

Production-breaking bug on a release:

1. `fix/#<issue>-<slug>` branch from `main` (issue still opened first).
2. Fix + regression test that fails without the fix.
3. PR → CI → squash merge.
4. **Patch tag** (`vX.Y.Z+1` of patch) → Release workflow ships the fixed
   APK.
5. If `main` has unreleased features, prefer tagging from the merge commit of
   the hotfix PR only when the tip is release-clean; otherwise release the
   next patch from `main` after reverting/deferring the risky change.

## 5. Issue hygiene

- Labels: `area/*` (where), `v1.0.0`/`v1.1.0`/`v1.2.0`/`v2` (when),
  `investigated` (spec complete), `workflow` (process meta), `triage` (needs
  assessment — set by the feature template), `user-requested` (owner's own
  feature requests — always prioritized right after bugs).
- The backlog lives in issues (scope labels), summarized in
  [ROADMAP.md](ROADMAP.md). When an issue lands, remove the roadmap bullet in
  the same docs sweep.
- An issue without acceptance criteria is not ready for `investigated` — an
  agent told to "fetch" it must first post the missing spec and wait for a
  thumbs-up if the gap is conceptual, not mechanical.

## 6. Worked example (this document's own landing)

Issue **#1** (*chore: bootstrap repository*) follows Direction A end-to-end:

```
#1  opened with spec + investigation + acceptance criteria        (Step 1)
chore/#1-bootstrap-repository branched from main                  (Step 2)
chore: bootstrap repository (#1) committed                        (Step 3)
PR "chore: bootstrap repository"                                  (Step 4)
CI green (unit tests + lint + debug APK build)                    (Step 5)
squash-merged -> issue #1 auto-closed                             (Step 5)
```

If you are an AI agent reading this before your first change: that transcript
is your template. Reproduce it, adapted to the issue you were given.
