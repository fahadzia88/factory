package com.toptier.custom.checkout.exception

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
