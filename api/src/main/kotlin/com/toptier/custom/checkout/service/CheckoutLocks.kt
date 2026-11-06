package com.toptier.custom.checkout.service

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * One git operation at a time per checkout. The lock is reentrant, so an operation can fetch while
 * holding it, and has no wait limit: each git call inside has its own timeout.
 */
@Component
class CheckoutLocks {

	private val locks = ConcurrentHashMap<Long, ReentrantLock>()

	/** Runs [block] while holding the lock of [checkoutId]. */
	fun <T> withLock(checkoutId: Long, block: () -> T): T {
		val lock = locks.computeIfAbsent(checkoutId) { ReentrantLock() }
		lock.lock()
		try {
			return block()
		} finally {
			lock.unlock()
		}
	}
}
