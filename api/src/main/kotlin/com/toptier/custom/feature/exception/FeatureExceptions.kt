package com.toptier.custom.feature.exception

/** A feature request that can't be carried out; the message is the response's `detail`. */
sealed class FeatureException(message: String) : RuntimeException(message)

/** The feature's name is empty, too long, or has no letter or digit to make a slug from. */
class InvalidFeatureNameException(message: String) : FeatureException(message)

/** The project's `feature/workflow.json` or requirements template is missing or invalid; the message names the file and the problem. */
class WorkflowInvalidException(message: String) : FeatureException(message)

/** No feature with that id in the project. */
class FeatureNotFoundException : FeatureException("Feature not found.")

/** The project is archived, so no feature can be started on it. */
class FeatureProjectArchivedException : FeatureException("This project is archived. Restore it to start a feature.")

/** [status] isn't one of the [allowed] states of the feature's workflow or of the step's type. */
class InvalidStatusException(val status: String?, val allowed: List<String>) :
	FeatureException("\"${status ?: ""}\" isn't one of this workflow's states: ${allowed.joinToString(", ")}.")

/** [type] isn't a step key of the feature's workflow; [allowed] are the keys. */
class InvalidStepTypeException(val type: String?, val allowed: List<String>) :
	FeatureException("\"${type ?: ""}\" isn't a step in this feature's workflow: ${allowed.joinToString(", ")}.")

/** A step's title or document path is invalid, or an update changes nothing. */
class InvalidStepException(message: String) : FeatureException(message)

/** No step with that id in the feature. */
class StepNotFoundException : FeatureException("Step not found.")

/** A requested file path is malformed, names `.git`, or leads outside the feature's folder. */
class InvalidFilePathException : FeatureException("That path isn't inside the feature's folder.")

/** Nothing at the requested path in the feature's folder, or it's a folder. */
class FeatureFileNotFoundException : FeatureException("That file doesn't exist.")

/** The requested file isn't a markdown (`.md`) file. */
class UnsupportedFileTypeException : FeatureException("Only markdown (.md) files can be opened.")

/** The requested file is over the size limit for reading. */
class FileTooLargeException : FeatureException("That file is larger than 200 KB.")

/** The feature's [branch] has no worktree, or its folder isn't on disk. */
class WorktreeMissingException(val branch: String) :
	FeatureException("The worktree for $branch is missing. Check it out again on the project page.")

/** The feature's chat is still answering an earlier message. */
class ChatBusyException :
	FeatureException("The agent is still answering the previous message. Wait for it or stop it.")

/** The agent couldn't be reached, rejected the API token, or answered with an error before streaming. */
class AgentUnavailableException(message: String) : FeatureException(message)

/** A chat message with no text. */
class BlankMessageException : FeatureException("message is required")

/** No task with that number in the feature. */
class TaskNotFoundException : FeatureException("Task not found.")

/** The task folder name isn't `NN-<layer>-<slug>`, or it has no `spec.md`. */
class InvalidTaskException(message: String) : FeatureException(message)

/** Some task specs aren't approved yet; [unapproved] are their paths. */
class SpecsNotApprovedException(val unapproved: List<String>) :
	FeatureException("Every task spec must be approved before implementation starts: ${unapproved.joinToString(", ")}.")

/** The task was started already. */
class TaskExistsException(number: Int) : FeatureException("Task %02d was started already.".format(number))

/** Tasks start in stack order; [expected] is the next number. */
class TaskOutOfOrderException(val expected: Int) :
	FeatureException("Tasks start in order; the next one is %02d.".format(expected))

/** The task below has no pull request yet. */
class PreviousTaskUnfinishedException(number: Int) :
	FeatureException("Task %02d has no pull request yet. Open it before starting the next task.".format(number))

/** The feature's docs worktree isn't on disk. */
class FeatureWorktreeMissingException(branch: String) :
	FeatureException("The worktree for $branch is missing. Check it out again on the project page.")

/** The task already has a pull request. */
class PullExistsException(number: Int) : FeatureException("Task %02d already has a pull request.".format(number))

/** The task's branch isn't on GitHub yet. */
class TaskNotPushedException(branch: String) : FeatureException("Push $branch before opening its pull request.")

/** A request body field is missing or invalid; the message names it. */
class InvalidFeatureRequestException(message: String) : FeatureException(message)

/** The task is already built on its effective base. */
class RestackNotNeededException(number: Int) : FeatureException("Task %02d is already up to date with its base.".format(number))

/** The task's worktree has uncommitted changes. */
class TaskDirtyException(branch: String) :
	FeatureException("$branch has uncommitted changes. Commit or discard them before restacking.")

/** The task's pull request was merged or closed. */
class TaskClosedException(number: Int) : FeatureException("Task %02d's pull request is merged or closed.".format(number))

/** The rebase hit conflicts in [files]; nothing was pushed and the branch is as before. */
class RestackConflictException(val files: List<String>) :
	FeatureException("The rebase conflicts in ${files.joinToString(", ")}. Nothing was pushed; resolve it by hand.")

/** One task that keeps the feature from completing, and why. */
data class UnfinishedTask(val task: Int, val reason: String)

/** Some tasks aren't merged yet; [unfinished] says which and why. */
class TasksUnfinishedException(val unfinished: List<UnfinishedTask>) :
	FeatureException("The feature can't be completed until every task's pull request is merged.")

/** A question set breaks the rules; the message names the question and the problem. */
class InvalidQuestionsException(message: String) : FeatureException(message)
