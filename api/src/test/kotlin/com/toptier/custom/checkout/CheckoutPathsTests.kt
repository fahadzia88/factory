package com.toptier.custom.checkout

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.service.CheckoutPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals

class CheckoutPathsTests {

	private val paths = CheckoutPaths(GitProperties(root = "/repos"))

	@Test
	fun `checkout and worktrees folders are named by project id`() {
		assertEquals(Path.of("/repos/12"), paths.checkoutDir(12))
		assertEquals(Path.of("/repos/12.worktrees"), paths.worktreesDir(12))
	}

	@Test
	fun `a worktree folder replaces slashes in the branch`() {
		assertEquals(
			Path.of("/repos/12.worktrees/feat__003-x__01-api-y"),
			paths.worktreeDir(12, "feat/003-x/01-api-y"),
		)
	}

	@Test
	fun `dot branches can't leave the worktrees folder`() {
		listOf("..", ".", "").forEach { branch ->
			assertThrows<IllegalArgumentException> { paths.worktreeDir(12, branch) }
		}
	}

	@Test
	fun `the root must be absolute`() {
		assertThrows<IllegalArgumentException> { GitProperties(root = "repos") }
		assertThrows<IllegalArgumentException> { GitProperties(root = " ") }
	}
}
