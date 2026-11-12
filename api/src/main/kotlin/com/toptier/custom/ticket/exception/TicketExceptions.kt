package com.toptier.custom.ticket.exception

import com.toptier.custom.ticket.domain.TicketStatus

/** A ticket request that can't be carried out; the message is the response's `detail`. */
sealed class TicketException(message: String) : RuntimeException(message)

/** No ticket with that id in the project. */
class TicketNotFoundException : TicketException("Ticket not found.")

/** A ticket field is missing or invalid; the message names it. */
class InvalidTicketException(message: String) : TicketException(message)

/** A filter value in the request isn't one the tracker knows; the message names it. */
class InvalidTicketRequestException(message: String) : TicketException(message)

/** The request tried to change the ticket's type, which is fixed when the ticket is created. */
class TicketTypeFixedException : TicketException("A ticket's type can't be changed after it is created.")

/** The ticket can't move from [from] to [to]; [allowed] are the statuses it can move to. */
class InvalidTransitionException(val from: TicketStatus, val to: TicketStatus, val allowed: List<TicketStatus>) :
	TicketException(
		"A ticket that is ${from.value} can't move to ${to.value}. " +
			if (allowed.isEmpty()) "It has no further status." else "It can move to: ${allowed.joinToString(", ") { it.value }}.",
	)

/** The ticket's workspace didn't fail, so there is nothing to retry. */
class WorkspaceNotNeededException : TicketException("This ticket's workspace didn't fail, so there is nothing to retry.")

/** The ticket has no usable workspace: it never became `in_progress`, or its worktree or `details.md` is missing. */
class WorkspaceMissingException(
	message: String = "This ticket has no workspace yet. It gets one when it first moves to in progress.",
) : TicketException(message)

/** The agent hasn't written findings into `details.md` yet, so there is nothing to approve. */
class NoFindingsException : TicketException("details.md has no findings yet. Ask the agent to investigate first.")

/** The investigation was approved already. */
class AlreadyApprovedException : TicketException("The investigation was approved already.")

/** The ticket's branch has no commit beyond the default branch, so there is nothing to push. */
class NothingCommittedException :
	TicketException("The branch has no commits beyond the default branch yet. Commit your changes first.")

/** The ticket's branch isn't on GitHub yet, so a pull request can't be opened for it. */
class BranchNotPushedException(branch: String) : TicketException("Push $branch before opening its pull request.")

/** The ticket's branch has no commit beyond the default branch, so there is nothing to put in a pull request. */
class NothingToCompareException :
	TicketException("The branch has no commits beyond the default branch, so there is nothing to compare.")

/** A pull request was already created from this ticket. */
class TicketPullExistsException : TicketException("A pull request was already created for this ticket.")

/** The ticket has a pull request link that was typed by hand; creating one replaces it only when confirmed. */
class PullLinkSetException :
	TicketException("This ticket has a pull request link you entered. Confirm to replace it with the new pull request.")

/** The project is archived, so its tickets can be read but not changed. */
class TicketProjectArchivedException : TicketException("This project is archived. Restore it to change its tickets.")
