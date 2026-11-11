package com.toptier.custom.feature.service

import com.toptier.custom.feature.domain.FeatureQuestion
import com.toptier.custom.feature.domain.FeatureQuestionRepository
import com.toptier.custom.feature.domain.QuestionStatus
import com.toptier.custom.feature.dto.OptionInput
import com.toptier.custom.feature.dto.QuestionInput
import com.toptier.custom.feature.dto.QuestionsRequest
import com.toptier.custom.feature.exception.InvalidQuestionsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** The questions the agent asks in a feature's chat: one pending set at a time, a newer set replacing it. */
@Service
class FeatureQuestions(
	private val featureService: FeatureService,
	private val questions: FeatureQuestionRepository,
	private val jsonMapper: JsonMapper,
	private val clock: Clock,
) {

	/** Validates and stores a set of questions as the feature's pending set. */
	@Transactional
	fun create(projectId: Long, featureId: Long, request: QuestionsRequest?): FeatureQuestion {
		featureService.load(projectId, featureId)
		val valid = QuestionValidator.validate(request)
		questions.supersedePending(featureId)
		return questions.saveAndFlush(FeatureQuestion().apply {
			this.featureId = featureId
			payload = jsonMapper.writeValueAsString(mapOf("questions" to valid))
			status = QuestionStatus.PENDING
			createdAt = clock.instant()
		})
	}
}

/** A question as stored: trimmed, with `multiSelect` set. */
data class Question(val header: String, val question: String, val multiSelect: Boolean, val options: List<QuestionOption>)

/** An option as stored. */
data class QuestionOption(val label: String, val description: String)

/** The rules for a question set; every refusal names the question and what to fix. */
internal object QuestionValidator {

	fun validate(request: QuestionsRequest?): List<Question> {
		val list = request?.questions
		if (list.isNullOrEmpty() || list.size > 4) throw InvalidQuestionsException("Ask 1 to 4 questions.")
		return list.mapIndexed { i, q -> question(i + 1, q) }
	}

	private fun question(n: Int, q: QuestionInput?): Question {
		if (q == null) throw InvalidQuestionsException("Question $n is empty.")
		val header = text(q.header, 12) ?: throw InvalidQuestionsException("Question $n: give a header of 1 to 12 characters.")
		val question = text(q.question, 300) ?: throw InvalidQuestionsException("Question $n: give the question in 1 to 300 characters.")
		val options = q.options
		if (options.isNullOrEmpty() || options.size !in 2..4) throw InvalidQuestionsException("Question $n: give 2 to 4 options.")
		val parsed = options.mapIndexed { i, option -> option(n, i + 1, option) }
		val labels = parsed.map { it.label.lowercase() }
		if (labels.toSet().size != labels.size) throw InvalidQuestionsException("Question $n: option labels must differ.")
		if ("other" in labels) throw InvalidQuestionsException("Question $n: don't offer \"Other\"; the app adds it.")
		return Question(header, question, q.multiSelect ?: false, parsed)
	}

	private fun option(n: Int, i: Int, option: OptionInput?): QuestionOption {
		val label = text(option?.label, 60) ?: throw InvalidQuestionsException("Question $n, option $i: give a label of 1 to 60 characters.")
		val description = text(option?.description, 200)
			?: throw InvalidQuestionsException("Question $n, option $i: give a description of 1 to 200 characters.")
		return QuestionOption(label, description)
	}

	/** [value] trimmed when it's 1 to [max] characters, else null. */
	private fun text(value: String?, max: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }
}
