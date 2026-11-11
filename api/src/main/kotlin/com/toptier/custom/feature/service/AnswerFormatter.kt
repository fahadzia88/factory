package com.toptier.custom.feature.service

import com.toptier.custom.feature.dto.AnswerInput
import com.toptier.custom.feature.exception.InvalidAnswersException

/** An answer as stored with its question set: the chosen labels and free text. */
data class Answer(val header: String, val selected: List<String>, val other: String?)

/** Checks answers against a question set and renders them as the user's message to the agent. */
object AnswerFormatter {

	private const val MAX_OTHER = 500

	/** The answers, one per question in order, each with known labels or "Other" text; throws [InvalidAnswersException]. */
	fun validate(questions: List<Question>, answers: List<AnswerInput>?): List<Answer> {
		if (answers.isNullOrEmpty()) throw InvalidAnswersException("Answer every question.")
		if (answers.size != questions.size) throw InvalidAnswersException("Answer all ${questions.size} questions, in order.")
		return questions.mapIndexed { i, question ->
			val n = i + 1
			val answer = answers[i]
			if (answer.header?.trim() != question.header) {
				throw InvalidAnswersException("Answer $n is for \"${answer.header ?: ""}\", but question $n is \"${question.header}\".")
			}
			val selected = answer.selected.orEmpty().map { it.trim() }
			val labels = question.options.map { it.label }
			selected.firstOrNull { it !in labels }?.let { throw InvalidAnswersException("Question $n has no option \"$it\".") }
			if (!question.multiSelect && selected.size > 1) throw InvalidAnswersException("Question $n takes one option.")
			val other = answer.other?.trim()?.takeIf { it.isNotEmpty() }
			if (other != null && other.length > MAX_OTHER) throw InvalidAnswersException("Question $n: keep \"Other\" to 500 characters.")
			if (selected.isEmpty() && other == null) throw InvalidAnswersException("Question $n: pick an option or write an answer.")
			Answer(question.header, selected, other)
		}
	}

	/** The message the agent gets: one numbered line per question, the recommended option marked. */
	fun format(questions: List<Question>, answers: List<Answer>): String = buildString {
		append("Answers to your questions:")
		questions.forEachIndexed { i, question ->
			val answer = answers[i]
			val recommended = question.options.first().label
			val parts = answer.selected.map { if (it == recommended) "$it (recommended)" else it } +
				listOfNotNull(answer.other?.let { "Other: $it" })
			append("\n${i + 1}. ${question.header}: ${parts.joinToString(", ")}")
		}
	}
}
