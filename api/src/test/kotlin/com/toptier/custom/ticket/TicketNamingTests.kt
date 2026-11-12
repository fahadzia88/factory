package com.toptier.custom.ticket

import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.service.TicketDetailsTemplate
import com.toptier.custom.ticket.service.TicketNaming
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketPriority
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TicketNamingTests {

	@Test
	fun `a slug is lowercase words joined by hyphens`() {
		assertEquals("login-fails-on-safari", TicketNaming.slug("Login fails on Safari!"))
		assertEquals("a-b-c", TicketNaming.slug("  A / B -- C  "))
		assertEquals("v2-0-api", TicketNaming.slug("v2.0 API"))
	}

	@Test
	fun `a slug with no letters or digits is ticket`() {
		assertEquals("ticket", TicketNaming.slug("!!!"))
		assertEquals("ticket", TicketNaming.slug("日本語"))
	}

	@Test
	fun `a long title is cut to 40 characters without a trailing hyphen`() {
		val slug = TicketNaming.slug("word ".repeat(30))

		assertEquals(true, slug.length <= 40)
		assertEquals(false, slug.endsWith("-"))
		assertEquals("word-word-word-word-word-word-word-word", slug)
	}

	@Test
	fun `branch and folder follow the ticket's type`() {
		assertEquals("bugs/3-login-fails", TicketNaming.branch(TicketType.BUG, 3, "login-fails"))
		assertEquals("feature/bugs/3-login-fails", TicketNaming.folder(TicketType.BUG, 3, "login-fails"))
		assertEquals("updates/12-dark-mode", TicketNaming.branch(TicketType.IMPROVEMENT, 12, "dark-mode"))
		assertEquals("feature/updates/12-dark-mode", TicketNaming.folder(TicketType.IMPROVEMENT, 12, "dark-mode"))
	}

	@Test
	fun `the template names the ticket, keeps its description and has empty findings and plan`() {
		val rendered = TicketDetailsTemplate.render(ticket(description = "Safari only\n| table"))

		assertContains(rendered.text, "# #3 Login fails")
		assertContains(rendered.text, "Type: bug · Priority: high · Status: in_progress")
		assertContains(rendered.text, "## Description\n\nSafari only\n| table\n")
		assertContains(rendered.text, "## Findings\n\nNot investigated yet.")
		assertContains(rendered.text, "## Plan\n\nNot written yet.")
		assertEquals(TicketDetailsTemplate.sha256(rendered.text), rendered.sha256)
	}

	@Test
	fun `a missing description reads as none, and any change to the file changes the fingerprint`() {
		val rendered = TicketDetailsTemplate.render(ticket(description = null))

		assertContains(rendered.text, "No description.")
		assertNotEquals(rendered.sha256, TicketDetailsTemplate.sha256(rendered.text.replace("Not investigated yet.", "Found it.")))
	}

	private fun ticket(description: String?) = Ticket().apply {
		number = 3
		type = TicketType.BUG
		title = "Login fails"
		this.description = description
		priority = TicketPriority.HIGH
		status = com.toptier.custom.ticket.domain.TicketStatus.IN_PROGRESS
	}
}
