package com.toptier.custom.feature.service

/** The titles and bodies of a feature's pull requests, and links into its repository. */
object PullText {

	/** `<NNN> <NN> <layer>: <task>`. */
	fun title(featureNumber: Int, taskNumber: Int, layer: String, task: String): String =
		"%03d %02d %s: %s".format(featureNumber, taskNumber, layer, task)

	/** `<NNN> <feature>: docs`. */
	fun docsTitle(featureNumber: Int, featureName: String): String = "%03d %s: docs".format(featureNumber, featureName)

	/** The task, a link to its spec, the ACs it covers, and the pull request below it in the stack. */
	fun body(task: String, specUrl: String, acs: List<String>, stackedOn: Int?): String = buildString {
		append("$task\n\nSpec: $specUrl\nACs covered: ${acs.joinToString(", ")}\n")
		if (stackedOn != null) append("\nStacked on #$stackedOn\n")
	}

	/** The docs pull request's body. */
	fun docsBody(code: String, folderUrl: String): String =
		"Docs for $code: requirements, task breakdown and task specs.\n\nFeature folder: $folderUrl\n"

	/** A file on a branch on GitHub; branch names keep their `/`. */
	fun blobUrl(owner: String, repo: String, branch: String, path: String): String =
		"https://github.com/$owner/$repo/blob/$branch/$path"
}
