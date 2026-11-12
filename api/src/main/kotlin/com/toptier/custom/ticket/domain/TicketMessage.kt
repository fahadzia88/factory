package com.toptier.custom.ticket.domain

import com.toptier.custom.feature.domain.MessageRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One message of a [Ticket]'s chat, kept so the ticket page can show the conversation again. */
@Entity
@Table(name = "ticket_message")
class TicketMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning [Ticket]. */
	@Column(name = "ticket_id", nullable = false)
	var ticketId: Long = 0

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	var role: MessageRole = MessageRole.USER

	@Column(nullable = false, columnDefinition = "TEXT")
	var text: String = ""

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH
}
