package com.toptier.custom.feature

import com.toptier.custom.feature.dto.OptionInput
import com.toptier.custom.feature.dto.QuestionInput
import com.toptier.custom.feature.dto.QuestionsRequest
import com.toptier.custom.feature.exception.InvalidQuestionsException
import com.toptier.custom.feature.service.QuestionValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class QuestionValidatorTests {

	@Test
	fun `a minimal set is valid and trimmed`() {
		val valid = QuestionValidator.validate(QuestionsRequest(listOf(question(header = " Token "))))

		assertEquals("Token", valid.single().header)
		assertEquals(false, valid.single().multiSelect)
		assertEquals(listOf("Keep GIT_TOKEN", "New token"), valid.single().options.map { it.label })
	}

	@Test
	fun `each rule names the question and the problem`() {
		refused(QuestionsRequest(null), "Ask 1 to 4 questions.")
		refused(QuestionsRequest(emptyList()), "Ask 1 to 4 questions.")
		refused(QuestionsRequest(List(5) { question() }), "Ask 1 to 4 questions.")
		refused(QuestionsRequest(listOf(question(), question(header = "x".repeat(13)))), "Question 2: give a header of 1 to 12 characters.")
		refused(QuestionsRequest(listOf(question(text = "x".repeat(301)))), "Question 1: give the question in 1 to 300 characters.")
		refused(QuestionsRequest(listOf(question(options = listOf(option("A"))))), "Question 1: give 2 to 4 options.")
		refused(QuestionsRequest(listOf(question(options = List(5) { option("O$it") }))), "Question 1: give 2 to 4 options.")
		refused(QuestionsRequest(listOf(question(options = listOf(option("A"), option("a"))))), "Question 1: option labels must differ.")
		refused(QuestionsRequest(listOf(question(options = listOf(option("A"), option("Other"))))), "Question 1: don't offer \"Other\"; the app adds it.")
		refused(QuestionsRequest(listOf(question(options = listOf(option("A"), option("other"))))), "Question 1: don't offer \"Other\"; the app adds it.")
		refused(
			QuestionsRequest(listOf(question(options = listOf(option("A"), option("B", description = " "))))),
			"Question 1, option 2: give a description of 1 to 200 characters.",
		)
	}

	private fun refused(request: QuestionsRequest?, message: String) {
		assertEquals(message, assertThrows<InvalidQuestionsException> { QuestionValidator.validate(request) }.message)
	}

	private fun question(
		header: String = "Token",
		text: String = "Which token does the API use for pull requests?",
		options: List<OptionInput> = listOf(option("Keep GIT_TOKEN"), option("New token")),
	) = QuestionInput(header, text, false, options)

	private fun option(label: String, description: String = "Why it's a good choice.") = OptionInput(label, description)
}
