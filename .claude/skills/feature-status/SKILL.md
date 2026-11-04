---
name: feature-status
description: Report where a feature is in the workflow — which gate it's at, which tasks are done or open, PR states, and which acceptance criteria are covered. Use when the user asks for a feature's status or which features are in progress.
argument-hint: [feature folder]
---

# Feature status

Read-only. Don't edit any files.

1. With no argument, list every `feature/NNN-*` folder with the status of its requirements and
   breakdown, one line each.
2. With a feature, report:
   - the gate it's at: requirements, breakdown, specs, implementation, or done;
   - each task's spec status, branch, PR link and PR state (`gh pr view <branch> --json state,
     baseRefName,reviewDecision` if `gh` is available);
   - any AC with no task or no verification;
   - anything out of order, such as a PR whose base doesn't match the spec, or a merged PR
     whose next branch hasn't been restacked;
   - the next action and which skill or session takes it.
