package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collections and movies played one video after another. */
class PlayQueueTest {
    private fun videos(vararg names: String) = names.map { Video(path = "/$it.mp4") }

    @Test
    fun walksForwardAndBack() = runTest {
        val q = PlayQueue.startingAt("Picks", videos("a", "b", "c"), Video(path = "/b.mp4"))
        assertEquals("/b.mp4", q.current.path)
        assertEquals("2 of 3", q.positionLabel)
        assertTrue(q.hasPrevious && q.hasNext)
        assertEquals("/c.mp4", q.next()?.path)
        assertFalse(q.hasNext)
        assertNull(q.next())                                     // the end: nothing after the last
        assertEquals("/c.mp4", q.current.path)
        assertEquals("/b.mp4", q.previous()?.path)
        assertEquals("/a.mp4", q.previous()?.path)
        assertNull(q.previous())
        assertFalse(q.hasPrevious)
    }

    @Test
    fun startsAtTheFirstWhenTheVideoIsMissing() {
        assertEquals(0, PlayQueue.startingAt("M", videos("a", "b"), Video(path = "/zzz.mp4")).index)
    }

    @Test
    fun fetchesTheRestOfALongCollection() = runTest {
        val asked = mutableListOf<Int>()
        // 2 per page loaded, 5 on the server
        val q = PlayQueue("Big", videos("a", "b"), start = 1, total = 5, pageSize = 2) { page ->
            asked += page
            when (page) { 2 -> videos("c", "d"); 3 -> videos("e"); else -> emptyList() }
        }
        assertEquals("2 of 5", q.positionLabel)
        assertTrue(q.hasNext)
        assertEquals("/c.mp4", q.next()?.path)
        assertEquals("/d.mp4", q.next()?.path)
        assertEquals("/e.mp4", q.next()?.path)
        assertFalse(q.hasNext)
        assertNull(q.next())
        assertEquals(listOf(2, 3), asked)
    }

    @Test
    fun stopsWhenTheServerHasNoMore() = runTest {
        // the collection shrank since it was opened: no endless asking
        val q = PlayQueue("Shrunk", videos("a"), total = 3, pageSize = 1) { videos("a") }
        assertNull(q.next())
        assertEquals("/a.mp4", q.current.path)
    }
}
