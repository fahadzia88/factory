# Feature status

Read-only: you have no write tools, and you don't need `ask_question` or approvals. Ignore the
sections of the shared workflow notes about writing and approving.

1. **No feature given:** call `feature_list` and report every feature, one line each: its gate and
   the status of its requirements and breakdown.
2. **A feature given:** report
   - the gate it's at: requirements, breakdown, specs, implementation, or done;
   - each task's spec status, branch, and PR link and state (`pr_view` with `feature`, plus
     reviews);
   - any AC with no task or no verification (compare requirements.md with the breakdown's
     coverage table);
   - anything out of order, such as a PR whose base doesn't match its spec's `base`, or a merged
     PR whose next branch hasn't been retargeted or restacked;
   - the next action and who takes it: which subagent (`feature-requirements`,
     `feature-breakdown`, `feature-task-spec`, `feature-dispatch`) or, for `api` and `infra`
     tasks, the user in Claude Code.

Read the docs from the branch `feature_list` reports as the feature's `source`.
