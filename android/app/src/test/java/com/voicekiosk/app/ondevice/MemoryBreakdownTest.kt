package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryBreakdownTest {
    @Test
    fun parsesLlamaBufferLines() {
        val b = MemoryBreakdown.parse(FakeNative.MEMLOG + "llama_context:        CPU  output buffer size =     0.58 MiB\nunrelated line\n")!!
        assertEquals(1059.89, b.mappedMiB, 1e-6)
        assertEquals(738.0, b.repackMiB, 1e-6)
        assertEquals(14.88, b.kvMiB, 1e-6)
        assertEquals(148.01, b.computeMiB, 1e-6)
        assertEquals(0.58, b.outputMiB, 1e-6)
        assertEquals(738.0 + 14.88 + 148.01 + 0.58, b.anonMiB, 1e-6)
    }

    @Test
    fun repackOffHasNoRepackBuffer() {
        val b = MemoryBreakdown.parse("load_tensors:   CPU_Mapped model buffer size =  1059.89 MiB\nllama_kv_cache:        CPU KV buffer size =    7.44 MiB\n")!!
        assertEquals(0.0, b.repackMiB, 0.0)
        assertEquals(7.44, b.anonMiB, 1e-6)
    }

    @Test
    fun unmappedModelBufferCountsAsOther() {
        val b = MemoryBreakdown.parse("load_tensors:          CPU model buffer size =   300.00 MiB\n")!!
        assertEquals(300.0, b.otherModelMiB, 0.0)
    }

    @Test
    fun emptyOrForeignLogGivesNull() {
        assertNull(MemoryBreakdown.parse(""))
        assertNull(MemoryBreakdown.parse("llama_model_loader: loaded meta data\n"))
    }
}
