#!/usr/bin/env bash
# PreToolUse hook for Edit|Write|NotebookEdit: stops Claude from editing files that come from
# the skeleton base repo, as listed in .claude/base-files. Active only in project repos (those
# with a `base` git remote); BASE_GUARD_FORCE=1 turns it on anywhere, for testing.

input=$(cat)
file=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input")
cwd=$(jq -r '.cwd // empty' <<<"$input")
[ -z "$file" ] && exit 0

root=$(git -C "${cwd:-$PWD}" rev-parse --show-toplevel 2>/dev/null) || exit 0
if [ "${BASE_GUARD_FORCE:-}" != 1 ] && ! git -C "$root" remote get-url base >/dev/null 2>&1; then
	exit 0
fi

case "$file" in
	"$root"/*) rel=${file#"$root"/} ;;
	/*) exit 0 ;;
	*) rel=$file ;;
esac

decide() {
	jq -n --arg d "$1" --arg r "$2" \
		'{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: $d, permissionDecisionReason: $r}}'
	exit 0
}

# The override list is the user's escape hatch, so Claude may never write to it.
if [ "$rel" = ".claude/base-overrides" ]; then
	decide deny "$rel is edited only by the user. Ask them to add the path there themselves."
fi

if [ -f "$root/.claude/base-overrides" ]; then
	while read -r pat; do
		[[ -z "$pat" || "$pat" == \#* ]] && continue
		# shellcheck disable=SC2053
		[[ "$rel" == $pat ]] && exit 0
	done <"$root/.claude/base-overrides"
fi

[ -f "$root/.claude/base-files" ] || exit 0
while read -r kind pat; do
	[[ -z "$kind" || "$kind" == \#* ]] && continue
	# shellcheck disable=SC2053
	[[ "$rel" == $pat ]] || continue
	if [ "$kind" = locked ]; then
		decide deny "$rel comes from the skeleton base repo and is locked in this project. Don't edit it, and don't work around this with shell commands. Judge the scope of the change: (1) if it would help every project built from the skeleton (a bug fix, security fix, shared component, convention or tooling change), stop and ask the user to make it in the skeleton repo, then merge base into this project — describe the change precisely; (2) if it's specific to this project, build it in project-owned code that extends the base (a new package, component, route or config property) without modifying this file; (3) if it truly needs a project-only change to this file, explain why to the user and ask them to add the path to .claude/base-overrides themselves."
	fi
	if [ "$kind" = shared ]; then
		decide ask "$rel is a shared skeleton file. Approve only if the change is project-specific and additive (new routes, nav items, dependencies, properties, services). A generic improvement belongs in the skeleton repo instead."
	fi
done <"$root/.claude/base-files"

exit 0
