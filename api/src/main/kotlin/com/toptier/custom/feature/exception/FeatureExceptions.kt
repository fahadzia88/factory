package com.toptier.custom.feature.exception

/** A feature request that can't be carried out; the message is the response's `detail`. */
sealed class FeatureException(message: String) : RuntimeException(message)

/** The feature's name is empty, too long, or has no letter or digit to make a slug from. */
class InvalidFeatureNameException(message: String) : FeatureException(message)

/** The project's `feature/workflow.json` or requirements template is missing or invalid; the message names the file and the problem. */
class WorkflowInvalidException(message: String) : FeatureException(message)

/** No feature with that id in the project. */
class FeatureNotFoundException : FeatureException("Feature not found.")
