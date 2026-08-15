package com.jingshanghui.pos.pos_device_adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PosDeviceAdapterPluginTest {
    @Test
    fun baseSnapshotUsesSupportedContractAndAdvertisesNoCapabilities() {
        val snapshot = PosDeviceAdapterPlugin().buildSnapshot()

        assertEquals("1.0", snapshot["contractVersion"])
        assertTrue((snapshot["capabilities"] as List<*>).isEmpty())
        assertTrue(snapshot["metadata"] is Map<*, *>)
    }
}
