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
