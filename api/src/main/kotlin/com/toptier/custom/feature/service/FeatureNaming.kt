package com.toptier.custom.feature.service

import com.toptier.custom.feature.exception.InvalidFeatureNameException
import java.util.Locale

/** The rules for a feature's name, slug, number, code, branch and folder. */
object FeatureNaming {

	private const val MAX_NAME = 100
	private const val MAX_SLUG = 40
	private val NON_SLUG = Regex("[^a-z0-9]+")
	private val NUMBERED = Regex("^(\\d{3,})-")

	/** [raw] trimmed; throws [InvalidFeatureNameException] when it's empty, too long or has no letter or digit. */
	fun name(raw: String?): String {
		val name = raw?.trim().orEmpty()
		if (name.isEmpty()) throw InvalidFeatureNameException("Enter a name.")
		if (name.length > MAX_NAME) throw InvalidFeatureNameException("Keep the name to 100 characters or fewer.")
		if (slug(name).isEmpty()) throw InvalidFeatureNameException("The name needs at least one letter or digit.")
		return name
	}

	/** [name] lowercased, runs of other characters as one `-`, at most 40 characters. */
	fun slug(name: String): String =
		name.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-').take(MAX_SLUG).trim('-')

	/** One more than the highest of [recordMax] and the numbers of [folders] (`feature/006-x` or `006-x`). */
	fun nextNumber(recordMax: Int?, folders: List<String>): Int {
		val folderMax = folders.mapNotNull { NUMBERED.find(it.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
		return 1 + maxOf(recordMax ?: 0, folderMax ?: 0)
	}

	/** `<NNN>-<slug>`. */
	fun code(number: Int, slug: String): String = "%03d-%s".format(number, slug)

	/** The docs branch every feature starts on. */
	fun branch(code: String): String = "feat/$code/00-docs"

	/** The feature's folder in the repository. */
	fun folder(code: String): String = "feature/$code"
}
