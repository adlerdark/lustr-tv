package com.lustr.tv.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PagerTest {
    private val data = (1..25).toList()

    private fun TestScope.pager(fail: () -> Boolean = { false }, errors: MutableList<IOException> = mutableListOf()) =
        Pager(this, pageSize = 10, onError = { errors += it }) { page, limit ->
            if (fail()) throw IOException("down")
            data.drop((page - 1) * limit).take(limit) to data.size
        }

    @Test
    fun pagesUntilTheEnd() = runTest {
        val p = pager()
        p.reset()
        advanceUntilIdle()
        assertEquals(10, p.state.value.items.size)
        assertEquals(25, p.state.value.total)
        p.loadMore(); advanceUntilIdle()
        p.loadMore(); advanceUntilIdle()
        assertEquals(data, p.state.value.items)
        assertTrue(p.state.value.endReached)
        p.loadMore(); advanceUntilIdle()                  // nothing more to load
        assertEquals(25, p.state.value.items.size)
    }

    @Test
    fun loadMoreWhileLoadingIsIgnored() = runTest {
        val p = pager()
        p.reset()
        p.loadMore()                                      // first page still loading
        advanceUntilIdle()
        assertEquals(10, p.state.value.items.size)
    }

    @Test
    fun errorStopsUntilRetry() = runTest {
        var down = true
        val errors = mutableListOf<IOException>()
        val p = pager({ down }, errors)
        p.reset(); advanceUntilIdle()
        assertEquals("down", p.state.value.error)
        assertEquals(1, errors.size)
        p.loadMore(); advanceUntilIdle()                  // no hammering while it fails
        assertEquals(1, errors.size)
        down = false
        p.retry(); advanceUntilIdle()
        assertNull(p.state.value.error)
        assertEquals(10, p.state.value.items.size)
    }

    @Test
    fun resetDropsTheOldPage() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val p = Pager(this, pageSize = 10) { page, _ ->
            calls++
            if (calls == 1) gate.await()                 // the old filter's page is slow
            listOf("call$calls-page$page") to 1
        }
        p.reset()
        advanceUntilIdle()
        p.reset()                                         // filters changed
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("call2-page1"), p.state.value.items)
        assertFalse(p.state.value.loading)
    }
}
