package com.toptier.custom.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move forward. */
class MutableClock(var now: Instant = Instant.parse("2026-10-26T12:00:00Z")) : Clock() {
	fun advance(duration: Duration) {
		now = now.plus(duration)
	}

	override fun instant(): Instant = now

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this
}
