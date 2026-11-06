package com.toptier.custom.checkout.controller

import com.toptier.custom.checkout.dto.AddWorktreeRequest
import com.toptier.custom.checkout.dto.WorktreeResponse
import com.toptier.custom.checkout.service.WorktreeService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The worktrees of a project's checkout, one per branch. */
@RestController
@RequestMapping("/api/projects/{id}/checkout/worktrees")
class WorktreeController(private val worktreeService: WorktreeService) {

	/** Fetches, adds a worktree for the branch and answers 201 with it. */
	@PostMapping
	fun add(@PathVariable id: Long, @RequestBody request: AddWorktreeRequest): ResponseEntity<WorktreeResponse> =
		ResponseEntity.status(HttpStatus.CREATED)
			.body(WorktreeResponse.from(worktreeService.add(id, request.branch, request.base)))

	/** Removes the worktree; 409 `worktree_unsaved` when it has work not on GitHub, unless `force=true`. */
	@DeleteMapping("/{worktreeId}")
	fun remove(
		@PathVariable id: Long,
		@PathVariable worktreeId: Long,
		@RequestParam(required = false) force: Boolean?,
	): ResponseEntity<Void> {
		worktreeService.remove(id, worktreeId, force == true)
		return ResponseEntity.noContent().build()
	}
}
