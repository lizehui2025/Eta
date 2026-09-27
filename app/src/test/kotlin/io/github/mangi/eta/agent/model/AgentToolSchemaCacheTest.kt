package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具 schema 缓存：能力不变必须复用同一实例，否则窗口预算的工具表估算缓存永远命不中。 */
class AgentToolSchemaCacheTest {
    private val base = AgentToolCapabilities.full()

    @Test
    fun identicalCapabilitiesReuseTheSameInstance() {
        val cache = AgentToolSchemaCache { capabilities -> JSONArray().put(capabilities.rootAvailable) }
        val first = cache.tools(base)
        repeat(50) { assertSame(first, cache.tools(base)) }
        assertEquals(1, cache.builds)
        assertEquals(50, cache.hits)
        assertTrue(cache.hitRate > 0.95)
    }

    @Test
    fun equalButDistinctCapabilitiesStillHit() {
        val cache = AgentToolSchemaCache { _ -> JSONArray().put("schema") }
        val first = cache.tools(AgentToolCapabilities.full())
        // 每轮都从采集点拿到新实例，但内容相同：必须命中。
        val second = cache.tools(AgentToolCapabilities.full(colorOs = true))
        assertSame(first, second)
        assertEquals(1, cache.builds)
    }

    @Test
    fun capabilityChangeBuildsANewInstanceExactlyOnce() {
        val cache = AgentToolSchemaCache { capabilities -> JSONArray().put(capabilities.notificationsAllowed) }
        val allowed = cache.tools(base)
        val denied = cache.tools(base.copy(notificationsAllowed = false))
        assertNotSame(allowed, denied)
        assertEquals(2, cache.builds)
        assertEquals(0, cache.hits)
        // 回到旧能力：仍然命中旧实例。
        assertSame(allowed, cache.tools(base))
        assertEquals(1, cache.hits)
    }

    @Test
    fun oscillatingCapabilitiesStayBoundedAndKeepHitting() {
        val cache = AgentToolSchemaCache(limit = 4) { capabilities -> JSONArray().put(capabilities.colorOs) }
        repeat(100) { index ->
            cache.tools(base.copy(accessibilityAvailable = index % 2 == 0))
        }
        assertTrue("缓存尺寸必须受限", cache.distinctCapabilities <= 4)
        assertTrue("两种能力交替时应几乎全部命中", cache.hitRate > 0.9)
    }
}
