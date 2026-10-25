package me.rerere.rikkahub.data.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PD01 协议单元测试。
 * CRC 与帧字节以公开协议文档（rhnvrm/catprinter docs/pd01-protocol.md）中的
 * 完整指令帧作为已知向量交叉验证，不依赖本实现的计算过程。
 */
class Pd01ProtocolTest {

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    @Test
    fun `crc8 matches known vectors from protocol doc`() {
        // 状态查询 payload 0x00 → crc 0x00（文档帧 5178 a3 00 01 00 00 00 00 ff）
        assertEquals(0x00, Pd01Protocol.crc8(byteArrayOf(0x00)))
        // 200DPI payload 0x32 → crc 0x9e（文档帧 5178 a4 00 01 00 32 9e ff）
        assertEquals(0x9e, Pd01Protocol.crc8(byteArrayOf(0x32)))
        // SET_PAPER payload 30 00 → crc 0xf9（文档帧 5178 a1 00 02 00 30 00 f9 ff）
        assertEquals(0xf9.toByte().toInt() and 0xFF, Pd01Protocol.crc8(byteArrayOf(0x30, 0x00)))
        // LATTICE_START payload → crc 0xa1；LATTICE_END payload → crc 0x11（文档帧）
        assertEquals(0xa1, Pd01Protocol.crc8(byteArrayOf(
            0xAA.toByte(), 0x55, 0x17, 0x38, 0x44, 0x5F.toByte(),
            0x5F.toByte(), 0x5F.toByte(), 0x44, 0x38, 0x2C.toByte()
        )))
        assertEquals(0x11, Pd01Protocol.crc8(byteArrayOf(
            0xAA.toByte(), 0x55, 0x17, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x17
        )))
        // 浓度 0x8000 大端 payload → crc 0xb6（CRC-8/poly 0x07 查表值）
        assertEquals(0xb6, Pd01Protocol.crc8(byteArrayOf(0x80.toByte(), 0x00)))
    }

    @Test
    fun `fixed command frames match protocol doc bytes`() {
        assertEquals("51 78 a3 00 01 00 00 00 ff", Pd01Protocol.stateQuery().toHex())
        assertEquals("51 78 a4 00 01 00 32 9e ff", Pd01Protocol.setQuality200Dpi().toHex())
        assertEquals("51 78 a1 00 02 00 30 00 f9 ff", Pd01Protocol.setPaper().toHex())
        assertEquals(
            "51 78 a6 00 0b 00 aa 55 17 38 44 5f 5f 5f 44 38 2c a1 ff",
            Pd01Protocol.latticeStart().toHex()
        )
        assertEquals(
            "51 78 a6 00 0b 00 aa 55 17 00 00 00 00 00 00 00 17 11 ff",
            Pd01Protocol.latticeEnd().toHex()
        )
    }

    @Test
    fun `energy command uses big endian payload`() {
        val frame = Pd01Protocol.setEnergy(0x8000)
        assertEquals("51 78 af 00 02 00 80 00 b6 ff", frame.toHex())
        // 越界钳制
        val maxFrame = Pd01Protocol.setEnergy(0x20000)
        assertEquals(0xFF.toByte(), maxFrame[6])
        assertEquals(0xFF.toByte(), maxFrame[7])
    }

    @Test
    fun `energyFromPercent maps presets and clamps`() {
        // 100% = 满能量；50% = 65535*50/100 向下取整 = 0x7FFF
        assertEquals(0xFFFF, Pd01Protocol.energyFromPercent(100))
        assertEquals(0x7FFF, Pd01Protocol.energyFromPercent(50))
        // 默认 55%（约 0x8CCC，介于原默认 0x8000 与深档之间）
        assertEquals(36044, Pd01Protocol.energyFromPercent(55))
        // 低于下限钳制到 30%，超出 100 钳制到 100%
        assertEquals(Pd01Protocol.energyFromPercent(30), Pd01Protocol.energyFromPercent(5))
        assertEquals(0xFFFF, Pd01Protocol.energyFromPercent(150))
        // 不低于协议最低能量 0x2000
        assertTrue(Pd01Protocol.energyFromPercent(30) >= 0x2000)
    }

    @Test
    fun `print row frame has correct layout`() {
        val row = ByteArray(Pd01Protocol.BYTES_PER_LINE) { it.toByte() }
        val frame = Pd01Protocol.printRow(row)
        assertEquals(Pd01Protocol.BYTES_PER_LINE + 8, frame.size)
        assertEquals(0x51.toByte(), frame[0])
        assertEquals(0x78.toByte(), frame[1])
        assertEquals(0xA2.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        assertEquals(48.toByte(), frame[4])           // LEN_LO
        assertEquals(0x00.toByte(), frame[5])         // LEN_HI
        assertEquals(0xFF.toByte(), frame[frame.size - 1]) // 帧尾
        // 帧内 CRC 与载荷一致
        val payload = frame.copyOfRange(6, frame.size - 2)
        assertEquals(Pd01Protocol.crc8(payload).toByte(), frame[frame.size - 2])
    }

    @Test
    fun `packRow maps pixels LSB-first with black as 1`() {
        // 全白
        val white = IntArray(Pd01Protocol.PRINT_WIDTH_DOTS) { 0xFFFFFFFF.toInt() }
        assertTrue(Pd01Protocol.packRow(white).all { it == 0.toByte() })

        // 全黑：所有位为 1
        val black = IntArray(Pd01Protocol.PRINT_WIDTH_DOTS) { 0xFF000000.toInt() }
        assertTrue(Pd01Protocol.packRow(black).all { it == 0xFF.toByte() })

        // 仅最左像素黑：byte0 bit0（LSB 在前）
        val leftDot = IntArray(Pd01Protocol.PRINT_WIDTH_DOTS) { x ->
            if (x == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val leftRow = Pd01Protocol.packRow(leftDot)
        assertEquals(1, leftRow[0].toInt() and 0x01)
        assertEquals(0, leftRow[0].toInt() and 0x02)

        // x=8 落在 byte1 bit0；x=15 落在 byte1 bit7
        val dots = IntArray(Pd01Protocol.PRINT_WIDTH_DOTS) { x ->
            if (x == 8 || x == 15) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val dotRow = Pd01Protocol.packRow(dots)
        assertEquals(1, dotRow[1].toInt() and 0x01)
        assertEquals(0x80, dotRow[1].toInt() and 0x80)
        assertEquals(0, dotRow[0].toInt())

        // 灰度阈值：亮度 128（<160）为黑，亮度 200（>=160）为白
        val gray = IntArray(Pd01Protocol.PRINT_WIDTH_DOTS) { x ->
            if (x % 2 == 0) 0xFF808080.toInt() else 0xFFC8C8C8.toInt()
        }
        val grayRow = Pd01Protocol.packRow(gray)
        // 偶数位全 1，奇数位全 0 → 交替模式 0x55
        assertEquals(0x55, grayRow[0].toInt() and 0xFF)
    }

    @Test
    fun `buildPrintPacket wraps rows with init and finish sequences`() {
        val whiteRow = ByteArray(Pd01Protocol.BYTES_PER_LINE)
        val packet = Pd01Protocol.buildPrintPacket(listOf(whiteRow), feedLines = 25, energy = 0x8000)
        val hex = packet.toHex()
        // 首帧为状态查询、随后 200DPI；行帧出现一次；尾部为 LATTICE_END + 状态查询
        assertTrue(hex.startsWith("51 78 a3 00 01 00 00 00 ff 51 78 a4"))
        assertTrue(hex.contains("51 78 a2 00 30 00"))
        assertTrue(hex.endsWith("51 78 a3 00 01 00 00 00 ff"))
        // LATTICE_START 与 LATTICE_END 各一次（以第 10 字节区分：38 / 00）
        assertEquals(1, Regex("51 78 a6 00 0b 00 aa 55 17 38").findAll(hex).count())
        assertEquals(1, Regex("51 78 a6 00 0b 00 aa 55 17 00").findAll(hex).count())
        assertEquals(1, Regex("51 78 bd 00 01 00 19").findAll(hex).count()) // 走纸 25 行
    }

    @Test
    fun `notification parse recognizes 5178 header`() {
        val complete = byteArrayOf(0x51, 0x78, 0xAE.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0xFF.toByte())
        val parsed = Pd01Notification.parse(complete)
        assertEquals(Pd01Notification.CMD_PRINT_COMPLETE, parsed?.command)
        assertEquals(null, Pd01Notification.parse(byteArrayOf(0x12, 0x34, 0x56)))
        assertEquals(null, Pd01Notification.parse(byteArrayOf(0x51, 0x78)))
    }
}
