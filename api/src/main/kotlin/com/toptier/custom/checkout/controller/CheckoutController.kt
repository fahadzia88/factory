package com.toptier.custom.checkout.controller

import com.toptier.custom.checkout.dto.CheckoutResponse
import com.toptier.custom.checkout.service.CheckoutService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A project's clone on the shared volume. */
@RestController
@RequestMapping("/api/projects/{id}/checkout")
class CheckoutController(private val checkoutService: CheckoutService) {

	/** The project's checkout with its worktrees; 404 `checkout_not_found` when it has none. */
	@GetMapping
	fun get(@PathVariable id: Long): CheckoutResponse = CheckoutResponse.from(checkoutService.get(id))

	/** Starts the clone (or retries a failed one) and answers 202 with the `cloning` checkout. */
	@PostMapping
	fun start(@PathVariable id: Long): ResponseEntity<CheckoutResponse> =
		ResponseEntity.status(HttpStatus.ACCEPTED).body(CheckoutResponse.from(checkoutService.start(id)))
}
