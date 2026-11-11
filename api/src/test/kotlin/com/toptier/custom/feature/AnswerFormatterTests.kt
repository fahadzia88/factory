package com.toptier.custom.feature

import com.toptier.custom.feature.dto.AnswerInput
import com.toptier.custom.feature.exception.InvalidAnswersException
import com.toptier.custom.feature.service.AnswerFormatter
import com.toptier.custom.feature.service.Question
import com.toptier.custom.feature.service.QuestionOption
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AnswerFormatterTests {

	private val questions = listOf(
		Question("Token", "Which token?", false, listOf(QuestionOption("Keep GIT_TOKEN", "a"), QuestionOption("New token", "b"))),
		Question("Layers", "Which layers?", true, listOf(QuestionOption("api", "a"), QuestionOption("app", "b"), QuestionOption("agent", "c"))),
	)

	@Test
	fun `answers are numbered, the recommended option marked, Other written out`() {
		val answers = AnswerFormatter.validate(
			questions,
			listOf(AnswerInput("Token", listOf("Keep GIT_TOKEN"), null), AnswerInput("Layers", listOf("app", "agent"), " and infra ")),
		)

		assertEquals(
			"Answers to your questions:\n1. Token: Keep GIT_TOKEN (recommended)\n2. Layers: app, agent, Other: and infra",
			AnswerFormatter.format(questions, answers),
		)
	}

	@Test
	fun `Other alone is an answer`() {
		val answers = AnswerFormatter.validate(
			questions,
			listOf(AnswerInput("Token", emptyList(), "split into two turns"), AnswerInput("Layers", listOf("api"), null)),
		)

		assertEquals(
			"Answers to your questions:\n1. Token: Other: split into two turns\n2. Layers: api (recommended)",
			AnswerFormatter.format(questions, answers),
		)
	}

	@Test
	fun `answers that don't fit are refused`() {
		fun refused(answers: List<AnswerInput>?) = assertThrows<InvalidAnswersException> { AnswerFormatter.validate(questions, answers) }

		refused(null)
		refused(listOf(AnswerInput("Token", listOf("Keep GIT_TOKEN"), null)))
		refused(listOf(AnswerInput("Token", listOf("Nope"), null), AnswerInput("Layers", listOf("api"), null)))
		refused(listOf(AnswerInput("Token", listOf("Keep GIT_TOKEN", "New token"), null), AnswerInput("Layers", listOf("api"), null)))
		refused(listOf(AnswerInput("Token", emptyList(), " "), AnswerInput("Layers", listOf("api"), null)))
		refused(listOf(AnswerInput("Layers", listOf("api"), null), AnswerInput("Token", listOf("New token"), null)))
		refused(listOf(AnswerInput("Token", emptyList(), "x".repeat(501)), AnswerInput("Layers", listOf("api"), null)))
	}
}
