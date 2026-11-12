package com.toptier.custom.ticket.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Stores [Ticket]s. */
interface TicketRepository : JpaRepository<Ticket, Long> {

	/** A project's tickets, newest number first. */
	fun findByProjectIdOrderByNumberDesc(projectId: Long): List<Ticket>

	/** The ticket with [id] when it belongs to [projectId], else null. */
	fun findByIdAndProjectId(id: Long, projectId: Long): Ticket?

	/** The project's highest ticket number, or null when it has none. */
	@Query("select max(t.number) from Ticket t where t.projectId = :projectId")
	fun maxNumber(projectId: Long): Int?
}
