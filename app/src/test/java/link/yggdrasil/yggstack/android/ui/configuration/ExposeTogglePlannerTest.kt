package link.yggdrasil.yggstack.android.ui.configuration

import link.yggdrasil.yggstack.android.data.ExposeMapping
import link.yggdrasil.yggstack.android.data.Protocol
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.service.ExposeMappingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposeTogglePlannerTest {

    private fun target(enabled: Boolean = false) = ExposeMapping(
        protocol = Protocol.TCP,
        localPort = 12002,
        localIp = "127.0.0.1",
        yggPort = 12002,
        enabled = enabled
    )

    @Test
    fun failedLiveApplyKeepsConfigUnchanged() {
        val config = YggstackConfig(exposeMappings = listOf(target()))
        val plan = planExposeToggle(
            config,
            target(),
            ExposeMappingResult(ok = false, message = "port is already in use")
        )

        assertNull("failed live apply must not produce a persisted config", plan.config)
        assertEquals("port is already in use", plan.error)
    }

    @Test
    fun successfulEnablePersistsTargetAndDisablesSibling() {
        val sibling = ExposeMapping(
            protocol = Protocol.TCP,
            localPort = 12002,
            localIp = "127.0.0.1",
            yggPort = 12003,
            enabled = true
        )
        val config = YggstackConfig(exposeMappings = listOf(target(), sibling))

        val plan = planExposeToggle(config, target(), ExposeMappingResult(ok = true))
        val updated = requireNotNull(plan.config)

        assertNull(plan.error)
        assertTrue(updated.exposeMappings.first { it.yggPort == 12002 }.enabled)
        assertFalse(updated.exposeMappings.first { it.yggPort == 12003 }.enabled)
    }

    @Test
    fun successfulDisablePersistsTargetOnly() {
        val sibling = ExposeMapping(
            protocol = Protocol.TCP,
            localPort = 12002,
            localIp = "127.0.0.1",
            yggPort = 12003,
            enabled = true
        )
        val config = YggstackConfig(exposeMappings = listOf(target(enabled = true), sibling))

        val plan = planExposeToggle(
            config,
            target(enabled = true),
            ExposeMappingResult(ok = true)
        )
        val updated = requireNotNull(plan.config)

        assertNotNull(updated)
        assertFalse(updated.exposeMappings.first { it.yggPort == 12002 }.enabled)
        assertTrue(updated.exposeMappings.first { it.yggPort == 12003 }.enabled)
    }
}
