package com.example.workclockr2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Executes independent reads concurrently while bounding simultaneous network requests.
 * Results retain the original input order.
 */
internal suspend fun <T, R> Iterable<T>.mapConcurrent(
    limit: Int,
    transform: suspend (T) -> R
): List<R> {
    require(limit > 0) { "并发数量必须大于 0" }
    val semaphore = Semaphore(limit)
    return coroutineScope {
        map { item ->
            async {
                semaphore.withPermit { transform(item) }
            }
        }.awaitAll()
    }
}
