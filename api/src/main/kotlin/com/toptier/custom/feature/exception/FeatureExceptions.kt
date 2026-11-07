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

/** The feature's chat is still answering an earlier message. */
class ChatBusyException :
	FeatureException("The agent is still answering the previous message. Wait for it or stop it.")

/** The agent couldn't be reached, rejected the API token, or answered with an error before streaming. */
class AgentUnavailableException(message: String) : FeatureException(message)

/** A chat message with no text. */
class BlankMessageException : FeatureException("message is required")
