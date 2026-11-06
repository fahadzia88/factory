package com.toptier.custom.pullrequest.controller

import com.toptier.custom.pullrequest.dto.PageResponse
import com.toptier.custom.pullrequest.dto.PullRequestFileResponse
import com.toptier.custom.pullrequest.dto.PullRequestResponse
import com.toptier.custom.pullrequest.dto.PullRequestSummaryResponse
import com.toptier.custom.pullrequest.service.PullRequestService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A project's pull requests, read live from GitHub. Read-only. */
@RestController
@RequestMapping("/api/projects/{id}/pulls")
class PullRequestController(private val pullRequestService: PullRequestService) {

	/** A page of 20 open pull requests, newest created first. */
	@GetMapping
	fun list(@PathVariable id: Long, @RequestParam(required = false) page: Int?): PageResponse<PullRequestSummaryResponse> =
		pullRequestService.list(id, page)

	/** One pull request, open, closed or merged. */
	@GetMapping("/{number}")
	fun get(@PathVariable id: Long, @PathVariable number: Int): PullRequestResponse = pullRequestService.get(id, number)

	/** A page of 30 changed files, in GitHub's order. */
	@GetMapping("/{number}/files")
	fun files(
		@PathVariable id: Long,
		@PathVariable number: Int,
		@RequestParam(required = false) page: Int?,
	): PageResponse<PullRequestFileResponse> = pullRequestService.files(id, number, page)
}
