package com.toptier.custom.ticket.domain

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * A bug or an improvement filed against a project, numbered per project. The workspace columns stay empty
 * until the ticket first becomes `in_progress`; [mode] is `investigator` until the user approves the investigation.
 */
@Entity
@Table(
	name = "ticket",
	uniqueConstraints = [UniqueConstraint(name = "uk_ticket_project_number", columnNames = ["project_id", "number"])],
)
class Ticket {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning project. */
	@Column(name = "project_id", nullable = false)
	var projectId: Long = 0

	@Column(nullable = false)
	var number: Int = 0

	/** Fixed at creation. */
	@Convert(converter = TicketTypeConverter::class)
	@Column(nullable = false, length = 20)
	var type: TicketType = TicketType.BUG

	@Column(nullable = false, length = 200)
	var title: String = ""

	@Column(columnDefinition = "TEXT")
	var description: String? = null

	@Convert(converter = TicketPriorityConverter::class)
	@Column(nullable = false, length = 20)
	var priority: TicketPriority = TicketPriority.MEDIUM

	@Convert(converter = TicketStatusConverter::class)
	@Column(nullable = false, length = 30)
	var status: TicketStatus = TicketStatus.FILED

	/** A pull request link, typed by the user or saved when the pull request was created from the ticket. */
	@Column(name = "pull_request_url", length = 500)
	var pullRequestUrl: String? = null

	/** The number of the pull request created from the ticket; null for a link typed by hand. */
	@Column(name = "pull_number")
	var pullNumber: Int? = null

	/** The title as a slug, fixed when the workspace is first made. */
	@Column(length = 60)
	var slug: String? = null

	@Column(length = 200)
	var branch: String? = null

	/** The ticket's folder in the repository, e.g. `feature/bugs/3-login-fails`. */
	@Column(length = 300)
	var folder: String? = null

	/** `none`, `ready` or `failed`. */
	@Column(name = "workspace_state", nullable = false, length = 10)
	var workspaceState: String = "none"

	@Column(name = "workspace_error", columnDefinition = "TEXT")
	var workspaceError: String? = null

	/** `investigator` or `implementer`. */
	@Column(nullable = false, length = 20)
	var mode: String = "investigator"

	@Column(name = "approved_at")
	var approvedAt: Instant? = null

	/** SHA-256 of `details.md` as first written, to tell whether the agent added findings. */
	@Column(name = "details_baseline", length = 64)
	var detailsBaseline: String? = null

	/** The agent's conversation for the ticket chat; null until the first turn. */
	@Column(name = "agent_session_id", length = 64)
	var agentSessionId: String? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
