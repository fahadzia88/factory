package com.toptier.custom.project.service

/** The owner and repository named by a `https://github.com/<owner>/<repo>` URL. */
data class GitHubRepositoryUrl(val owner: String, val repo: String) {

	companion object {
		private val PATTERN = Regex("""^https://github\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(\.git)?/?$""")

		/**
		 * Parses exactly `https://github.com/<owner>/<repo>`, optionally ending in `.git` or `/`; anything else
		 * (another host, a query, a fragment, extra path segments) is null. [repo] drops the `.git` suffix.
		 */
		fun parse(url: String): GitHubRepositoryUrl? {
			val match = PATTERN.matchEntire(url) ?: return null
			val (owner, repo) = match.destructured
			if (repo == "." || repo == "..") return null
			return GitHubRepositoryUrl(owner, repo)
		}
	}
}
