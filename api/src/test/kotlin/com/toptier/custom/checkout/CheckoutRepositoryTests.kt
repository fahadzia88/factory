package com.toptier.custom.checkout

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
class CheckoutRepositoryTests {

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@Test
	fun `a project has at most one checkout`() {
		checkouts.saveAndFlush(checkout(1))

		assertThrows<DataIntegrityViolationException> { checkouts.saveAndFlush(checkout(1)) }
	}

	@Test
	fun `a branch has at most one worktree per checkout`() {
		val a = checkouts.saveAndFlush(checkout(1)).id!!
		val b = checkouts.saveAndFlush(checkout(2)).id!!
		worktrees.saveAndFlush(worktree(a, "feat/x"))
		worktrees.saveAndFlush(worktree(b, "feat/x"))

		assertThrows<DataIntegrityViolationException> { worktrees.saveAndFlush(worktree(a, "feat/x")) }
	}

	@Test
	fun `updateState moves only rows in the given state`() {
		val cloning = checkouts.saveAndFlush(checkout(1, CheckoutState.CLONING)).id!!
		val ready = checkouts.saveAndFlush(checkout(2, CheckoutState.READY)).id!!

		val changed = checkouts.updateState(CheckoutState.CLONING, CheckoutState.FAILED, "The clone was interrupted by a restart.")

		assertEquals(1, changed)
		checkouts.findById(cloning).get().let {
			assertEquals(CheckoutState.FAILED, it.state)
			assertEquals("The clone was interrupted by a restart.", it.error)
		}
		checkouts.findById(ready).get().let {
			assertEquals(CheckoutState.READY, it.state)
			assertNull(it.error)
		}
		assertEquals(listOf(cloning), checkouts.findAllByState(CheckoutState.FAILED).map { it.id })
		assertEquals(ready, checkouts.findByProjectId(2)?.id)
	}

	@Test
	fun `worktrees list main first, then oldest first`() {
		val id = checkouts.saveAndFlush(checkout(1)).id!!
		val newer = worktrees.saveAndFlush(worktree(id, "feat/b", Instant.ofEpochSecond(20)))
		val older = worktrees.saveAndFlush(worktree(id, "feat/a", Instant.ofEpochSecond(10)))
		val main = worktrees.saveAndFlush(worktree(id, "main", Instant.ofEpochSecond(30), main = true))

		assertEquals(
			listOf(main.id, older.id, newer.id),
			worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(id).map { it.id },
		)
		assertEquals(main.id, worktrees.findByIdAndCheckoutId(main.id!!, id)?.id)
		assertNull(worktrees.findByIdAndCheckoutId(main.id!!, id + 1))
	}

	@Test
	fun `deleteByCheckoutId removes only that checkout's worktrees`() {
		val a = checkouts.saveAndFlush(checkout(1)).id!!
		val b = checkouts.saveAndFlush(checkout(2)).id!!
		worktrees.saveAndFlush(worktree(a, "main", main = true))
		worktrees.saveAndFlush(worktree(a, "feat/x"))
		worktrees.saveAndFlush(worktree(b, "main", main = true))

		worktrees.deleteByCheckoutId(a)

		assertEquals(0, worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(a).size)
		assertEquals(1, worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(b).size)
		assertEquals(true, worktrees.existsByCheckoutIdAndBranch(b, "main"))
		assertEquals(false, worktrees.existsByCheckoutIdAndBranch(a, "main"))
	}

	private fun checkout(projectId: Long, state: CheckoutState = CheckoutState.READY) = RepositoryCheckout().apply {
		this.projectId = projectId
		path = "/repos/$projectId"
		this.state = state
		createdAt = Instant.EPOCH
	}

	private fun worktree(checkoutId: Long, branch: String, createdAt: Instant = Instant.EPOCH, main: Boolean = false) =
		RepositoryWorktree().apply {
			this.checkoutId = checkoutId
			this.branch = branch
			path = "/repos/$checkoutId.worktrees/${branch.replace("/", "__")}"
			this.main = main
			this.createdAt = createdAt
		}
}
