package com.toptier.custom.pullrequest.dto

import com.toptier.custom.github.service.GitHubFile
import com.toptier.custom.github.service.GitHubPage
import com.toptier.custom.github.service.GitHubPull
import java.time.Instant

/** One page of a GitHub list; GitHub gives no totals, so [hasNext] says whether another page follows. */
data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val hasNext: Boolean) {
	companion object {
		fun <S, T> from(page: GitHubPage<S>, number: Int, size: Int, map: (S) -> T) =
			PageResponse(page.items.map(map), number, size, page.hasNext)
	}
}

/** Who opened a pull request. */
data class PullRequestAuthor(val login: String, val avatarUrl: String)

/** A pull request in a list. */
data class PullRequestSummaryResponse(
	val number: Int,
	val title: String,
	val state: String,
	val draft: Boolean,
	val author: PullRequestAuthor?,
	val headRef: String,
	val baseRef: String,
	val createdAt: Instant,
	val updatedAt: Instant,
	val htmlUrl: String,
) {
	companion object {
		fun from(pull: GitHubPull) = PullRequestSummaryResponse(
			number = pull.number,
			title = pull.title,
			state = state(pull),
			draft = pull.draft,
			author = pull.user?.let { PullRequestAuthor(it.login, it.avatarUrl) },
			headRef = headRef(pull),
			baseRef = pull.base.ref,
			createdAt = pull.createdAt,
			updatedAt = pull.updatedAt,
			htmlUrl = pull.htmlUrl,
		)

		/** GitHub only says open or closed; a closed pull request with a merge time was merged. */
		fun state(pull: GitHubPull) = if (pull.mergedAt != null) "merged" else pull.state

		/** A branch in a fork reads `owner:branch`; a deleted fork (no head repo) counts as one. */
		fun headRef(pull: GitHubPull) =
			if (pull.head.repo == null || pull.head.repo.fullName != pull.base.repo?.fullName) pull.head.label else pull.head.ref
	}
}

/** One pull request with its counts and description. */
data class PullRequestResponse(
	val number: Int,
	val title: String,
	val state: String,
	val draft: Boolean,
	val author: PullRequestAuthor?,
	val headRef: String,
	val baseRef: String,
	val createdAt: Instant,
	val updatedAt: Instant,
	val htmlUrl: String,
	val body: String?,
	val commits: Int,
	val additions: Int,
	val deletions: Int,
	val changedFiles: Int,
	val mergedAt: Instant?,
	val closedAt: Instant?,
) {
	companion object {
		fun from(pull: GitHubPull): PullRequestResponse {
			val summary = PullRequestSummaryResponse.from(pull)
			return PullRequestResponse(
				number = summary.number,
				title = summary.title,
				state = summary.state,
				draft = summary.draft,
				author = summary.author,
				headRef = summary.headRef,
				baseRef = summary.baseRef,
				createdAt = summary.createdAt,
				updatedAt = summary.updatedAt,
				htmlUrl = summary.htmlUrl,
				body = pull.body?.takeIf { it.isNotBlank() },
				commits = pull.commits ?: 0,
				additions = pull.additions ?: 0,
				deletions = pull.deletions ?: 0,
				changedFiles = pull.changedFiles ?: 0,
				mergedAt = pull.mergedAt,
				closedAt = pull.closedAt,
			)
		}
	}
}

/** A file a pull request changes; [patch] is null when GitHub sends none (binary or too large). */
data class PullRequestFileResponse(
	val filename: String,
	val previousFilename: String?,
	val status: String,
	val additions: Int,
	val deletions: Int,
	val patch: String?,
	val htmlUrl: String,
) {
	companion object {
		fun from(file: GitHubFile) = PullRequestFileResponse(
			filename = file.filename,
			previousFilename = file.previousFilename,
			status = file.status,
			additions = file.additions,
			deletions = file.deletions,
			patch = file.patch,
			htmlUrl = file.blobUrl,
		)
	}
}
