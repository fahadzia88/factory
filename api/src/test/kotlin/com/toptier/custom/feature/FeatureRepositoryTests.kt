package com.toptier.custom.feature

import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureMessage
import com.toptier.custom.feature.domain.FeatureMessageRepository
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.domain.MessageRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
class FeatureRepositoryTests {

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var steps: FeatureStepRepository

	@Autowired
	lateinit var messages: FeatureMessageRepository

	@Test
	fun `a number is used once per project`() {
		features.saveAndFlush(feature(1, 4))
		features.saveAndFlush(feature(2, 4))

		assertThrows<DataIntegrityViolationException> { features.saveAndFlush(feature(1, 4)) }
	}

	@Test
	fun `features list newest number first, with the project's highest number`() {
		features.saveAndFlush(feature(1, 2))
		features.saveAndFlush(feature(1, 5))
		features.saveAndFlush(feature(2, 9))

		assertEquals(listOf(5, 2), features.findByProjectIdOrderByNumberDesc(1).map { it.number })
		assertEquals(5, features.maxNumber(1))
		assertNull(features.maxNumber(3))
	}

	@Test
	fun `a feature is found only in its own project`() {
		val id = features.saveAndFlush(feature(1, 1)).id!!

		assertEquals(id, features.findByIdAndProjectId(id, 1)?.id)
		assertNull(features.findByIdAndProjectId(id, 2))
	}

	@Test
	fun `steps list in position order`() {
		val featureId = features.saveAndFlush(feature(1, 1)).id!!
		steps.saveAndFlush(step(featureId, 2))
		val first = steps.saveAndFlush(step(featureId, 1)).id!!

		assertEquals(listOf(1, 2), steps.findByFeatureIdOrderByPositionAsc(featureId).map { it.position })
		assertEquals(2, steps.maxPosition(featureId))
		assertNull(steps.maxPosition(featureId + 1))
		assertEquals(first, steps.findByIdAndFeatureId(first, featureId)?.id)
		assertNull(steps.findByIdAndFeatureId(first, featureId + 1))
		assertThrows<DataIntegrityViolationException> { steps.saveAndFlush(step(featureId, 1)) }
	}

	@Test
	fun `messages list oldest first and keep long text`() {
		val featureId = features.saveAndFlush(feature(1, 1, workflow = "x".repeat(10_000))).id!!
		messages.saveAndFlush(message(featureId, MessageRole.ASSISTANT, "y".repeat(10_000), Instant.parse("2026-11-06T10:01:00Z")))
		messages.saveAndFlush(message(featureId, MessageRole.USER, "hello", Instant.parse("2026-11-06T10:00:00Z")))

		val listed = messages.findByFeatureIdOrderByCreatedAtAscIdAsc(featureId)
		assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), listed.map { it.role })
		assertEquals(10_000, listed[1].text.length)
		assertEquals(10_000, features.findById(featureId).get().workflow.length)
	}

	private fun feature(projectId: Long, number: Int, workflow: String = "{}") = Feature().apply {
		this.projectId = projectId
		this.number = number
		name = "Feature $number"
		slug = "feature-$number"
		status = "requirements"
		this.workflow = workflow
		branch = "feat/%03d-feature-$number/00-docs".format(number)
		folder = "feature/%03d-feature-$number".format(number)
		createdAt = NOW
		updatedAt = NOW
	}

	private fun step(featureId: Long, position: Int) = FeatureStep().apply {
		this.featureId = featureId
		this.position = position
		typeKey = "spec"
		title = "Spec $position"
		status = "draft"
		createdAt = NOW
		updatedAt = NOW
	}

	private fun message(featureId: Long, role: MessageRole, text: String, at: Instant) = FeatureMessage().apply {
		this.featureId = featureId
		this.role = role
		this.text = text
		createdAt = at
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-11-06T10:00:00Z")
	}
}
