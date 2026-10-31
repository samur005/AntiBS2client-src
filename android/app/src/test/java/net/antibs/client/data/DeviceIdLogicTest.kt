package net.antibs.client.data

import net.antibs.client.data.DeviceIdLogic.DevType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 1.0.59: LAN device identification helpers. */
class DeviceIdLogicTest {
    private fun name(o: ByteArrayOutputStream, n: String) { for (l in n.split('.')) { val b = l.toByteArray(); o.write(b.size); o.write(b) }; o.write(0) }
    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 8); o.write(v and 255) }
    private fun rr(o: ByteArrayOutputStream, n: String, type: Int, data: ByteArray) { name(o, n); u16(o, type); u16(o, 1); u16(o, 0); u16(o, 120); u16(o, data.size); o.write(data) }

    @Test fun mdnsRecords() {
        val o = ByteArrayOutputStream()
        u16(o, 0); u16(o, 0x8400); u16(o, 0); u16(o, 4); u16(o, 0); u16(o, 0)
        val inst = "Living Room._googlecast._tcp.local"
        rr(o, "_googlecast._tcp.local", 12, ByteArrayOutputStream().also { name(it, inst) }.toByteArray())
        rr(o, inst, 16, ByteArrayOutputStream().also { for (s in listOf("md=Chromecast Ultra", "fn=Гостиная")) { val b = s.toByteArray(); it.write(b.size); it.write(b) } }.toByteArray())
        rr(o, inst, 33, ByteArrayOutputStream().also { u16(it, 0); u16(it, 0); u16(it, 8009); name(it, "cc-1.local") }.toByteArray())
        rr(o, "cc-1.local", 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val recs = DeviceIdLogic.parseRecords(o.toByteArray())
        assertEquals(4, recs.size)
        val acc = HashMap<String, DeviceIdLogic.MdnsInfo>()
        DeviceIdLogic.mergeMdns(acc, "192.168.1.50", recs)
        val i = acc.getValue("192.168.1.50")
        assertEquals("Гостиная", i.name); assertEquals("Chromecast Ultra", i.model); assertTrue("googlecast" in i.services)
        assertEquals(DevType.TV, DeviceIdLogic.classify(DeviceIdLogic.Evidence(mdns = i)))
    }

    @Test fun upnpAndHttp() {
        val x = "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Samsung Q60</friendlyName>" +
            "<manufacturer>Samsung Electronics</manufacturer><modelName>QE55Q60</modelName></device></root>"
        val u = DeviceIdLogic.parseUpnp(x)
        assertEquals("[TV] Samsung Q60", u.friendlyName); assertEquals("Samsung Electronics", u.manufacturer); assertEquals("QE55Q60", u.modelName)
        assertEquals(DevType.TV, DeviceIdLogic.classify(DeviceIdLogic.Evidence(upnp = u)))
        assertEquals("Samsung Electronics · QE55Q60", DeviceIdLogic.makeModel(DeviceIdLogic.Evidence(upnp = u)))
        assertEquals("Keenetic Web", DeviceIdLogic.htmlTitle("<html><head><TITLE>\n Keenetic  Web </TITLE>"))
        val h = DeviceIdLogic.headers("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:1900/desc.xml\r\nST: upnp:rootdevice\r\n")
        assertEquals("http://192.168.1.1:1900/desc.xml", h["location"])
    }

    @Test fun classifyPorts() {
        fun c(vararg p: Int, gw: Boolean = false) = DeviceIdLogic.classify(DeviceIdLogic.Evidence(isGateway = gw, ports = p.toList()))
        assertEquals(DevType.ROUTER, c(80, gw = true))
        assertEquals(DevType.PRINTER, c(9100, 80))
        assertEquals(DevType.CAMERA, c(554, 80))
        assertEquals(DevType.TV, c(8009))
        assertEquals(DevType.PHONE, c(62078))
        assertEquals(DevType.PC, c(445, 139))
        assertEquals(DevType.NAS, c(5000, 445))
        assertEquals(DevType.UNKNOWN, c(22))
        assertEquals(DevType.PC, DeviceIdLogic.classify(DeviceIdLogic.Evidence(netbios = "DESKTOP-1")))
        assertEquals(DevType.SPEAKER, DeviceIdLogic.classify(DeviceIdLogic.Evidence(mdns = DeviceIdLogic.MdnsInfo("Яндекс Станция", null, setOf("http")))))
        assertEquals(DevType.IOT, DeviceIdLogic.classify(DeviceIdLogic.Evidence(mdns = DeviceIdLogic.MdnsInfo("Lamp", null, setOf("hap")))))
    }
}
