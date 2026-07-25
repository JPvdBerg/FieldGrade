package com.fieldgrade.app.transport

import com.fieldgrade.app.logging.SessionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 Tier-A validation: tablet transport & link supervision (pure JVM). */
class TransportTest {

    private class FakeClock(var t: Long = 0) : Clock {
        override fun nowMs(): Long = t
    }

    private class FakeLink : ByteLink {
        override var isOpen = true
        val written = ArrayList<ByteArray>()
        private val inbound = ArrayDeque<Byte>()
        fun feed(s: String) { for (b in s.toByteArray(Charsets.UTF_8)) inbound.addLast(b) }
        override fun write(bytes: ByteArray) { written.add(bytes) }
        override fun read(): ByteArray {
            val out = ByteArray(inbound.size)
            var i = 0
            while (inbound.isNotEmpty()) out[i++] = inbound.removeFirst()
            return out
        }
        override fun close() { isOpen = false }
    }

    // Reference strings produced by tools/controller_simulator.py (cross-language parity).
    private val refCommand =
        """{"v":1,"seq":1042,"ts_ms":0,"mode":"AUTO","target_mm":-18,"manual":0,"enable":true,"crc16":33262}"""
    private val refStatus =
        """{"v":1,"seq_ack":1042,"state":"ACTIVE","output":-274,"supply_mv":13780,"estop":false,"fault":0,"age_ms":34,"crc16":30287}"""

    // ---- CRC + cross-language parity ----
    @Test fun crc_known_vector() {
        assertEquals(0x29B1, ControllerProtocol.crc16Ccitt("123456789".toByteArray()))
    }

    @Test fun encode_matches_python_and_firmware_byte_for_byte() {
        val bytes = ControllerProtocol.encode(
            ControlCommand(seq = 1042, ts_ms = 0, mode = "AUTO", target_mm = -18, manual = 0, enable = true)
        )
        assertEquals(refCommand, String(bytes, Charsets.UTF_8).trim())
    }

    @Test fun decode_valid_status_from_controller() {
        val s = ControllerProtocol.decodeStatus(refStatus)
        assertNotNull(s)
        assertEquals(1042L, s!!.seq_ack)
        assertEquals("ACTIVE", s.state)
        assertEquals(-274, s.output)
        assertEquals(13780, s.supply_mv)
        assertEquals(0, s.fault)
    }

    // ---- CRC verify on receive ----
    @Test fun decode_rejects_corrupted_crc() {
        val corrupt = refStatus.replace("\"crc16\":30287", "\"crc16\":30288")
        assertNull(ControllerProtocol.decodeStatus(corrupt))
    }

    @Test fun decode_rejects_malformed_json() {
        assertNull(ControllerProtocol.decodeStatus("{not json"))
    }

    @Test fun decode_rejects_tampered_payload_even_if_crc_field_present() {
        // change output but keep the old CRC -> must fail verification
        val tampered = refStatus.replace("\"output\":-274", "\"output\":900")
        assertNull(ControllerProtocol.decodeStatus(tampered))
    }

    // ---- framing across chunk boundaries ----
    @Test fun framer_reassembles_split_and_multiple_frames() {
        val framer = LineFramer()
        assertTrue(framer.offer("{\"a\":1}".toByteArray()).isEmpty())      // no newline yet
        val out = framer.offer("\n{\"b\":2}\n{\"c\":3}\n".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "{\"c\":3}"), out)
    }

    // ---- link supervision ----
    @Test fun supervisor_connects_goes_stale_and_recovers() {
        val clock = FakeClock(0)
        val sup = LinkSupervisor(clock, staleAfterMs = 250)
        sup.onOpen()
        sup.onStatus(ControllerProtocol.decodeStatus(refStatus)!!)
        clock.t = 200; assertEquals(LinkState.CONNECTED, sup.poll())
        clock.t = 300; assertEquals(LinkState.STALE, sup.poll())
        clock.t = 305; sup.onStatus(ControllerProtocol.decodeStatus(refStatus)!!)
        assertEquals(LinkState.CONNECTED, sup.poll())
    }

    @Test fun supervisor_surfaces_fault_and_never_suppresses_it() {
        val clock = FakeClock(0)
        val sup = LinkSupervisor(clock)
        sup.onOpen()
        sup.onStatus(StatusFrame(seq_ack = 5, state = "ESTOP", estop = true, fault = 2))
        assertEquals(LinkState.FAULTED, sup.state)
        assertEquals(2, sup.currentFault)
    }

    // ---- command streamer ----
    @Test fun streamer_emits_monotonic_sequence_at_cadence() {
        val clock = FakeClock(0)
        val link = FakeLink()
        val sup = LinkSupervisor(clock)
        sup.onOpen()
        val streamer = CommandStreamer(link, sup, clock, SessionLog(), periodMs = 40)
        streamer.enable = true; streamer.mode = "AUTO"; streamer.targetMm = -10
        var t = 0L
        while (t <= 200) { clock.t = t; streamer.tick(); t += 10 }
        // sends at 0,40,80,120,160,200 -> 6 commands
        assertEquals(6L, streamer.seq)
        assertEquals(6, link.written.size)
        // sequence numbers strictly increasing
        val seqs = link.written.map { String(it).substringAfter("\"seq\":").substringBefore(",").toLong() }
        assertEquals((1L..6L).toList(), seqs)
    }

    @Test fun streamer_stops_sending_when_link_closed() {
        val clock = FakeClock(0)
        val link = FakeLink()
        val sup = LinkSupervisor(clock)
        sup.onOpen()
        val streamer = CommandStreamer(link, sup, clock, periodMs = 40)
        streamer.enable = true
        clock.t = 0; streamer.tick()
        val before = streamer.seq
        link.close()
        clock.t = 5000; streamer.tick()
        assertEquals(before, streamer.seq)             // nothing sent after link loss
    }

    @Test fun streamer_decodes_inbound_status_and_logs() {
        val clock = FakeClock(0)
        val link = FakeLink()
        val sup = LinkSupervisor(clock)
        sup.onOpen()
        val log = SessionLog()
        val streamer = CommandStreamer(link, sup, clock, log, periodMs = 40)
        link.feed(refStatus + "\n")
        clock.t = 10; streamer.tick()
        assertEquals("ACTIVE", sup.lastStatus?.state)
        assertTrue(log.size >= 1)
        assertTrue(log.export().contains("state=ACTIVE"))
    }

    @Test fun streamer_drops_bad_inbound_frame() {
        val clock = FakeClock(0)
        val link = FakeLink()
        val sup = LinkSupervisor(clock)
        sup.onOpen()
        val log = SessionLog()
        val streamer = CommandStreamer(link, sup, clock, log, periodMs = 40)
        link.feed(refStatus.replace("30287", "30288") + "\n")   // bad CRC
        clock.t = 10; streamer.tick()
        assertNull(sup.lastStatus)                     // never trusted
        assertTrue(log.export().contains("DROP invalid status frame"))
    }
}
