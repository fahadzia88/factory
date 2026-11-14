package com.toptier.custom.cluster.service

/**
 * Published inside [ClusterService.delete]'s transaction, so features that keep data per cluster
 * (the project links) can clean it up. Listeners run in that transaction.
 */
data class ClusterDeletedEvent(val clusterId: Long)
