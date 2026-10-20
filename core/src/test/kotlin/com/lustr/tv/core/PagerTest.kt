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
            Page(data.drop((page - 1) * limit).take(limit), data.size)
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
            Page(listOf("call$calls-page$page"), 1)
        }
        p.reset()
        advanceUntilIdle()
        p.reset()                                         // filters changed
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("call2-page1"), p.state.value.items)
        assertFalse(p.state.value.loading)
    }

    @Test
    fun jumpThenScrollBothWays() = runTest {
        val p = pager()                                   // 25 items, pages of 10
        p.reset(); advanceUntilIdle()
        assertTrue(p.jumpTo(5))                           // already loaded: nothing to do
        assertFalse(p.jumpTo(21))                         // page 3 holds it
        advanceUntilIdle()
        assertEquals(20, p.state.value.offset)
        assertEquals((21..25).toList(), p.state.value.items)
        assertTrue(p.state.value.endReached)
        assertTrue(p.state.value.hasPrevious)
        p.loadPrevious(); advanceUntilIdle()               // scrolling up
        assertEquals(10, p.state.value.offset)
        assertEquals((11..25).toList(), p.state.value.items)
        p.loadPrevious(); advanceUntilIdle()
        assertEquals(0, p.state.value.offset)
        assertEquals(data, p.state.value.items)
        assertFalse(p.state.value.hasPrevious)
        p.loadPrevious(); advanceUntilIdle()               // at the start: nothing
        assertEquals(25, p.state.value.items.size)
    }

    @Test
    fun lettersSurviveAJump() = runTest {
        val letters = listOf(LetterIndex("A", 0), LetterIndex("M", 12))
        val p = Pager(this, pageSize = 10) { page, limit -> Page(data.drop((page - 1) * limit).take(limit), data.size, letters) }
        p.reset(); advanceUntilIdle()
        assertEquals(letters, p.state.value.letters)
        p.jumpTo(12)
        assertEquals(letters, p.state.value.letters)       // the bar stays while the page loads
        advanceUntilIdle()
        assertEquals(10, p.state.value.offset)
    }

    @Test
    fun lettersFromEachPageAddUp() = runTest {
        // movie titles: letters worked out per page (offsets included), merged as pages arrive
        val titles = listOf("Alpha", "Avatar", "Bravo", "Charlie", "Delta", "Echo")
        val p = Pager(this, pageSize = 3) { page, limit ->
            val chunk = titles.drop((page - 1) * limit).take(limit)
            Page(chunk, titles.size, Letters.index(chunk, offset = (page - 1) * limit))
        }
        p.reset(); advanceUntilIdle()
        p.loadMore(); advanceUntilIdle()
        assertEquals(listOf("A" to 0, "B" to 2, "C" to 3, "D" to 4, "E" to 5), p.state.value.letters.map { it.letter to it.index })
    }
}
