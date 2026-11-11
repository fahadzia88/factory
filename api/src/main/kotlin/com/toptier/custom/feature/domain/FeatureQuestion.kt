package com.toptier.custom.feature.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A set of questions the agent asked in a feature's chat, and the user's answers once given. */
@Entity
@Table(name = "feature_question")
class FeatureQuestion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning [Feature]. */
	@Column(name = "feature_id", nullable = false)
	var featureId: Long = 0

	/** The questions as JSON: `{ "questions": [ … ] }`. */
	@Column(nullable = false, columnDefinition = "TEXT")
	var payload: String = ""

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	var status: QuestionStatus = QuestionStatus.PENDING

	/** The answers as JSON once given; null before. */
	@Column(columnDefinition = "TEXT")
	var answers: String? = null

	/** The assistant message the questions were asked after; null until that reply is stored. */
	@Column(name = "after_message_id")
	var afterMessageId: Long? = null

	/** The user message that answered them; null before. */
	@Column(name = "answer_message_id")
	var answerMessageId: Long? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "answered_at")
	var answeredAt: Instant? = null
}
