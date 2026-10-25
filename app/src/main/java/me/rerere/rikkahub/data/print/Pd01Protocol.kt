package me.rerere.rikkahub.data.print

/**
 * PD01 热敏打印机 0x5178 协议。
 *
 * 帧格式：`51 78 CMD 00 LEN_LO LEN_HI PAYLOAD... CRC8(PAYLOAD) FF`
 * - LEN 为 16 位小端；CRC8 仅对 PAYLOAD 计算（多项式 0x07，初值 0，MSB first）
 * - 点阵行：384 点 = 48 字节，字节内 LSB 在前（bit0 = 最左像素），1 = 加热（黑）
 *
 * 打印序列（经 catprinter Go 实现、pd01-printer-mcp 参考实现与协议文档三方验证）：
 * 状态查询 → 200DPI → 设置浓度 → 应用浓度 → LATTICE_START → 逐行数据 →
 * 走纸 → SET_PAPER → LATTICE_END → 状态查询。
 *
 * 协议规则参考 rhnvrm/catprinter（MIT）与 1124823599331l-boop/pd01-printer-mcp（MIT），
 * 本实现为符合 Android 工程结构的 Kotlin 重写，未复制原始代码。
 */
object Pd01Protocol {

    /** 打印头宽度（点） */
    const val PRINT_WIDTH_DOTS = 384

    /** 每行点阵字节数（384 / 8） */
    const val BYTES_PER_LINE = PRINT_WIDTH_DOTS / 8

    // GATT 标识（作为线索使用，连接时仍需校验服务与特征属性）
    const val SERVICE_UUID = "0000ae30-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHARACTERISTIC_UUID = "0000ae01-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_CHARACTERISTIC_UUID = "0000ae02-0000-1000-8000-00805f9b34fb"

    /** 打印默认浓度（范围 0x2000..0xFFFF，参考实现验证过的中档值） */
    const val DEFAULT_ENERGY = 0x8000

    /** 浓度百分比下限（过低能量会导致字迹极浅无法阅读） */
    const val MIN_ENERGY_PERCENT = 30

    /** 浓度百分比 → 协议能量值（线性映射到 0x2000..0xFFFF） */
    fun energyFromPercent(percent: Int): Int {
        val safePercent = percent.coerceIn(MIN_ENERGY_PERCENT, 100)
        return (0xFFFF * safePercent / 100).coerceIn(0x2000, 0xFFFF)
    }

    /** 打印完成后走纸行数（撕纸预留） */
    const val FEED_LINES = 25

    /** 灰度转黑白阈值（与参考实现一致：亮度 < 160 视为黑） */
    const val BLACK_LUMINANCE_THRESHOLD = 160

    private const val HEADER0 = 0x51
    private const val HEADER1 = 0x78
    private const val TERMINATOR = 0xFF

    // 指令码
    private const val CMD_SET_PAPER = 0xA1
    private const val CMD_PRINT_ROW = 0xA2
    private const val CMD_GET_DEV_STATE = 0xA3
    private const val CMD_SET_QUALITY = 0xA4
    private const val CMD_LATTICE = 0xA6
    private const val CMD_SET_ENERGY = 0xAF
    private const val CMD_FEED_PAPER = 0xBD
    private const val CMD_APPLY_ENERGY = 0xBE

    /** CRC8 校验（多项式 0x07，初值 0，仅对 payload 计算） */
    fun crc8(payload: ByteArray): Int {
        var crc = 0
        for (value in payload) {
            crc = crc xor (value.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }

    /** 构造完整指令帧 */
    fun frame(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = payload.size
        val out = ByteArray(8 + length)
        out[0] = HEADER0.toByte()
        out[1] = HEADER1.toByte()
        out[2] = command.toByte()
        out[3] = 0x00
        out[4] = (length and 0xFF).toByte()
        out[5] = ((length shr 8) and 0xFF).toByte()
        payload.copyInto(out, 6)
        out[6 + length] = crc8(payload).toByte()
        out[7 + length] = TERMINATOR.toByte()
        return out
    }

    /** 查询设备状态（触发固件自检，打印前必发） */
    fun stateQuery(): ByteArray = frame(CMD_GET_DEV_STATE, byteArrayOf(0x00))

    /** 设置 200DPI 打印质量 */
    fun setQuality200Dpi(): ByteArray = frame(CMD_SET_QUALITY, byteArrayOf(0x32))

    /** 设置打印浓度（两字节大端，范围 0x2000..0xFFFF） */
    fun setEnergy(energy: Int = DEFAULT_ENERGY): ByteArray {
        val safe = energy.coerceIn(0x2000, 0xFFFF)
        return frame(CMD_SET_ENERGY, byteArrayOf(((safe shr 8) and 0xFF).toByte(), (safe and 0xFF).toByte()))
    }

    /** 应用浓度设置 */
    fun applyEnergy(): ByteArray = frame(CMD_APPLY_ENERGY, byteArrayOf(0x01))

    /** 点阵传输开始标记 */
    fun latticeStart(): ByteArray = frame(
        CMD_LATTICE,
        byteArrayOf(
            0xAA.toByte(), 0x55, 0x17, 0x38, 0x44, 0x5F.toByte(),
            0x5F.toByte(), 0x5F.toByte(), 0x44, 0x38, 0x2C.toByte()
        )
    )

    /** 点阵传输结束标记 */
    fun latticeEnd(): ByteArray = frame(
        CMD_LATTICE,
        byteArrayOf(
            0xAA.toByte(), 0x55, 0x17, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x17
        )
    )

    /** 纸张设置（连续纸） */
    fun setPaper(): ByteArray = frame(CMD_SET_PAPER, byteArrayOf(0x30, 0x00))

    /** 发送一行点阵（48 字节，LSB 在前，1 = 黑） */
    fun printRow(row: ByteArray): ByteArray {
        require(row.size == BYTES_PER_LINE) { "print row must be $BYTES_PER_LINE bytes" }
        return frame(CMD_PRINT_ROW, row)
    }

    /** 走纸（1..255 行） */
    fun feedPaper(lines: Int = FEED_LINES): ByteArray {
        val safe = lines.coerceIn(1, 255)
        return frame(CMD_FEED_PAPER, byteArrayOf(safe.toByte()))
    }

    /**
     * 灰度像素行 → 点阵行。
     * @param pixels ARGB 像素数组，长度必须为 [PRINT_WIDTH_DOTS]
     * @param threshold 亮度阈值，低于视为黑（加热）
     */
    fun packRow(pixels: IntArray, threshold: Int = BLACK_LUMINANCE_THRESHOLD): ByteArray {
        require(pixels.size == PRINT_WIDTH_DOTS) { "row must contain $PRINT_WIDTH_DOTS pixels" }
        val row = ByteArray(BYTES_PER_LINE)
        for (x in 0 until PRINT_WIDTH_DOTS) {
            val argb = pixels[x]
            val red = (argb shr 16) and 0xFF
            val green = (argb shr 8) and 0xFF
            val blue = argb and 0xFF
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            if (luminance < threshold) {
                row[x / 8] = (row[x / 8].toInt() or (1 shl (x % 8))).toByte()
            }
        }
        return row
    }

    /** 条带 Bitmap → 点阵行序列（逐行取像素后打包；调用方负责 Bitmap 生命周期） */
    fun bitmapToRows(bitmap: android.graphics.Bitmap, threshold: Int = BLACK_LUMINANCE_THRESHOLD): List<ByteArray> {
        require(bitmap.width == PRINT_WIDTH_DOTS) {
            "bitmap width must be $PRINT_WIDTH_DOTS, got ${bitmap.width}"
        }
        val pixels = IntArray(PRINT_WIDTH_DOTS)
        val rows = ArrayList<ByteArray>(bitmap.height)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            rows.add(packRow(pixels, threshold))
        }
        return rows
    }

    /**
     * 打印序列分段：适配层按此顺序经 BLE 逐段发送（分段以便流式处理条带）。
     */
    fun initSequence(energy: Int = DEFAULT_ENERGY): List<ByteArray> = listOf(
        stateQuery(),
        setQuality200Dpi(),
        setEnergy(energy),
        applyEnergy(),
        latticeStart(),
    )

    /** 打印序列收尾：走纸 + 纸张设置 + 结束标记 + 状态查询 */
    fun finishSequence(feedLines: Int = FEED_LINES): List<ByteArray> = listOf(
        feedPaper(feedLines),
        setPaper(),
        latticeEnd(),
        stateQuery(),
    )

    /**
     * 完整打印数据包（一次性组装全部行；仅适用于短内容，超长内容请用
     * [initSequence] + 逐行 + [finishSequence] 流式发送）。
     */
    fun buildPrintPacket(rows: List<ByteArray>, feedLines: Int = FEED_LINES, energy: Int = DEFAULT_ENERGY): ByteArray {
        val parts = buildList {
            addAll(initSequence(energy))
            rows.forEach { add(printRow(it)) }
            addAll(finishSequence(feedLines))
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}

/** 打印机状态通知（解析自 ae02 通知特征，仅作辅助信号，不作硬依赖） */
data class Pd01Notification(val command: Int, val payload: ByteArray) {
    companion object {
        private const val HEADER0 = 0x51
        private const val HEADER1 = 0x78

        /** 打印完成通知指令码（协议文档报告值） */
        const val CMD_PRINT_COMPLETE = 0xAE

        fun parse(data: ByteArray): Pd01Notification? {
            val headerOk = data.size >= 4 &&
                data[0] == HEADER0.toByte() && data[1] == HEADER1.toByte()
            if (!headerOk) return null
            val command = data[2].toInt() and 0xFF
            val payload = data.copyOfRange(4, data.size)
            return Pd01Notification(command, payload)
        }
    }
}
