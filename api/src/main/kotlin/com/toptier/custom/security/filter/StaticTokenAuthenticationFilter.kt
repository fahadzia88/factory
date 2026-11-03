package com.toptier.custom.security.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Authenticates requests carrying `Authorization: Bearer <token>` that matches the configured static token.
 * Requests without a valid token are left unauthenticated and rejected by the security chain.
 */
class StaticTokenAuthenticationFilter(apiToken: String) : OncePerRequestFilter() {

	private val expected = apiToken.toByteArray()

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
		val token = request.getHeader(HttpHeaders.AUTHORIZATION)
			?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
			?.substring(BEARER_PREFIX.length)
			?.trim()

		if (token != null && MessageDigest.isEqual(expected, token.toByteArray())) {
			val authentication = UsernamePasswordAuthenticationToken.authenticated(
				API_CLIENT, null, AuthorityUtils.createAuthorityList("ROLE_API"),
			)
			SecurityContextHolder.getContext().authentication = authentication
		}

		filterChain.doFilter(request, response)
	}

	companion object {
		private const val BEARER_PREFIX = "Bearer "
		private const val API_CLIENT = "api-client"
	}
}
