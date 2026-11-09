package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.FeatureFileContentResponse
import com.toptier.custom.feature.dto.FeatureFileTreeResponse
import com.toptier.custom.feature.service.FeatureFiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The files in a feature's folder, read from its worktree. Read-only: both routes are `GET`, never
 * accept a body, and nothing here writes, renames or deletes a file.
 */
@RestController
@RequestMapping("/api/projects/{id}/features/{featureId}/files")
class FeatureFilesController(private val featureFiles: FeatureFiles) {

	/** The feature's folder tree; empty when the folder doesn't exist yet. */
	@GetMapping
	fun tree(@PathVariable id: Long, @PathVariable featureId: Long): FeatureFileTreeResponse =
		FeatureFileTreeResponse.from(featureFiles.tree(id, featureId))

	/** One markdown file in the feature's folder; [path] is relative to that folder. */
	@GetMapping("/content")
	fun content(@PathVariable id: Long, @PathVariable featureId: Long, @RequestParam path: String): FeatureFileContentResponse =
		FeatureFileContentResponse.from(featureFiles.read(id, featureId, path))
}
