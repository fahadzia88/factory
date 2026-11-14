package com.toptier.custom.cluster.controller

import com.toptier.custom.cluster.dto.ClusterRequest
import com.toptier.custom.cluster.dto.ClusterResponse
import com.toptier.custom.cluster.service.ClusterService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Clusters: the HomeLab Controllers this app talks to, each a name and an address. */
@RestController
@RequestMapping("/api/clusters")
class ClusterController(private val clusterService: ClusterService) {

	/** Every cluster, ordered by name. */
	@GetMapping
	fun list(): List<ClusterResponse> = clusterService.list().map(ClusterResponse::from)

	/** Creates a cluster; answers 201 with its location. */
	@PostMapping
	fun create(@RequestBody request: ClusterRequest): ResponseEntity<ClusterResponse> {
		val cluster = ClusterResponse.from(clusterService.create(request.toInput()))
		return ResponseEntity.created(URI("/api/clusters/${cluster.id}")).body(cluster)
	}

	/** Replaces a cluster's name and address. */
	@PutMapping("/{id}")
	fun update(@PathVariable id: Long, @RequestBody request: ClusterRequest): ClusterResponse =
		ClusterResponse.from(clusterService.update(id, request.toInput()))

	/** Removes a cluster; projects linked to it are unlinked. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: Long) = clusterService.delete(id)
}
