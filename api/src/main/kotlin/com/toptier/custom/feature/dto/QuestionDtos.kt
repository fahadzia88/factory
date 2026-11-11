package com.toptier.custom.feature.dto

/** Body of `POST …/features/{featureId}/questions`: 1 to 4 questions; the first option is the recommended one. */
data class QuestionsRequest(val questions: List<QuestionInput>? = null)

/** One question with 2 to 4 options; "Other" isn't sent, the app adds it. */
data class QuestionInput(
	val header: String? = null,
	val question: String? = null,
	val multiSelect: Boolean? = false,
	val options: List<OptionInput>? = null,
)

/** One option of a question. */
data class OptionInput(val label: String? = null, val description: String? = null)

/** A stored question set. */
data class QuestionsCreated(val id: Long)
