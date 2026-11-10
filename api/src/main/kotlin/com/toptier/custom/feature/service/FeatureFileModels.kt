package com.toptier.custom.feature.service

import java.time.Instant

/** The folder tree of a feature: [root] is the feature's folder in the repository, [entries] its contents. */
data class FeatureFileTree(val root: String, val entries: List<FeatureFileNode>)

/**
 * A file or folder in a feature's folder; [path] is relative to that folder with `/` separators.
 * [markdown] is set only on `.md` files, and [children] only on folders.
 */
data class FeatureFileNode(
	val name: String,
	val path: String,
	val directory: Boolean,
	val markdown: Boolean,
	val children: List<FeatureFileNode>,
)

/** A markdown file read from a feature's folder: its UTF-8 [content], [size] in bytes and last modified time. */
data class FeatureFileContent(val path: String, val content: String, val size: Long, val updatedAt: Instant)
