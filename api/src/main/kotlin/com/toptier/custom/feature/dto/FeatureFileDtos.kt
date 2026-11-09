package com.toptier.custom.feature.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.toptier.custom.feature.service.FeatureFileContent
import com.toptier.custom.feature.service.FeatureFileNode
import com.toptier.custom.feature.service.FeatureFileTree
import java.time.Instant

/** Body of `GET …/features/{featureId}/files`: the feature's folder ([root]) and its contents. */
data class FeatureFileTreeResponse(val root: String, val entries: List<FeatureFileEntryResponse>) {
	companion object {
		fun from(tree: FeatureFileTree) = FeatureFileTreeResponse(tree.root, tree.entries.map(FeatureFileEntryResponse::from))
	}
}

/**
 * A file or folder in the tree; [type] is `directory` or `file`. Files carry [markdown] and no
 * [children]; folders carry [children] (possibly empty) and no [markdown].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeatureFileEntryResponse(
	val name: String,
	val path: String,
	val type: String,
	val markdown: Boolean?,
	val children: List<FeatureFileEntryResponse>?,
) {
	companion object {
		fun from(node: FeatureFileNode): FeatureFileEntryResponse =
			if (node.directory) {
				FeatureFileEntryResponse(node.name, node.path, "directory", null, node.children.map(::from))
			} else {
				FeatureFileEntryResponse(node.name, node.path, "file", node.markdown, null)
			}
	}
}

/** Body of `GET …/files/content`: one markdown file, its [size] in bytes and last modified time. */
data class FeatureFileContentResponse(val path: String, val content: String, val size: Long, val updatedAt: Instant) {
	companion object {
		fun from(file: FeatureFileContent) = FeatureFileContentResponse(file.path, file.content, file.size, file.updatedAt)
	}
}
