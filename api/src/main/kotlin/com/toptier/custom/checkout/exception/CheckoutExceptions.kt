package com.toptier.custom.checkout.exception

import com.toptier.custom.checkout.service.UnsavedWork

/** A checkout request that can't be carried out; the message is the response's `detail`. */
sealed class CheckoutException(message: String) : RuntimeException(message)

/** The project has no checkout. */
class CheckoutNotFoundException : CheckoutException("This project has no repository checkout.")

/** The project's checkout is cloning or ready, so another can't be started. */
class CheckoutExistsException : CheckoutException("This project already has a checkout.")

/** The operation needs a ready checkout, and this one is cloning or failed. */
class CheckoutNotReadyException : CheckoutException("The checkout isn't ready yet.")

/** GitHub refused or couldn't serve a fetch, push or clone; the message is plain words, with no git output. */
class GitRemoteException(val failure: GitFailure) : CheckoutException(message(failure)) {
	private companion object {
		fun message(failure: GitFailure) = when (failure) {
			GitFailure.NOT_FOUND -> "GitHub couldn't find the repository, or the git token can't see it."
			GitFailure.AUTH ->
				"GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read and write on this repository."
			GitFailure.UNREACHABLE -> "GitHub couldn't be reached. Try again."
			else -> "Git couldn't complete the request with GitHub. Try again."
		}
	}
}

/** [name] isn't a valid git branch name. */
class InvalidBranchException(name: String?) : CheckoutException("\"${name ?: ""}\" isn't a valid branch name.")

/** The base for a new branch isn't on origin or in the checkout. */
class BaseNotFoundException(base: String) :
	CheckoutException("Base branch $base doesn't exist on GitHub or in the checkout.")

/** The branch already has a worktree (the main one included), or its folder is taken by another branch. */
class BranchCheckedOutException(message: String) : CheckoutException(message) {
	companion object {
		/** The branch itself is in a worktree. */
		fun of(branch: String) = BranchCheckedOutException("$branch is already checked out in a worktree.")

		/** Another branch's worktree has the folder [branch] maps to. */
		fun folder(branch: String, folder: String) =
			BranchCheckedOutException("$branch would use the folder $folder, which another worktree has.")
	}
}

/** No worktree with that id in the project's checkout. */
class WorktreeNotFoundException : CheckoutException("Worktree not found.")

/** The main worktree is the clone itself and can't be removed. */
class MainWorktreeException : CheckoutException("The main worktree can't be removed.")

/** Removing the worktree would lose [unsaved] work; removing with force discards it. */
class WorktreeUnsavedException(val unsaved: UnsavedWork) :
	CheckoutException("This worktree has changes that aren't on GitHub. Remove it with force to discard them.")
