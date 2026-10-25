package me.rerere.rikkahub.data.print

import android.graphics.Bitmap

/** 一次打印的结果度量 */
data class PrintOutcome(
    val rowsSent: Int,      // 实际已写入的点阵行数
    val totalRows: Int,     // 点阵总行数
) {
    /** 是否有数据已发出（用于区分"发送前失败"与"发送中断"） */
    val hasDataSent: Boolean get() = rowsSent > 0
}

/**
 * 打印机协议适配器：负责协议初始化、图像编码、发送顺序与收尾。
 * 通用打印工具层、任务管理层与 Markdown 渲染层不依赖具体协议实现，
 * 新增协议（如 ESC/POS）时实现本接口即可接入，无需改动上层。
 *
 * 打印为阻塞式执行（任务管理器负责调度到 Dispatchers.IO）；
 * 取消采用协作式 [shouldAbort] 检查——发送中途取消只能停止后续数据，
 * 打印机可能已缓存并打印部分内容。
 */
interface PrinterAdapter {

    val protocol: PrinterProtocol

    /** 连接自检：发送无害指令（不产生出纸），验证链路与固件响应能力 */
    fun verify(connection: PrinterConnection)

    /**
     * 执行完整打印：初始化序列 → 逐条带（Bitmap → 点阵行 → 协议帧）→ 收尾序列。
     *
     * @param markdown 待打印的 Markdown 内容
     * @param shouldAbort 协作式取消检查（条带间与数据块间都会检查）
     * @param onRowsSent 进度回调（已发送行数, 总行数）
     * @throws PrintException 连接 / 写入 / 取消等异常（携带 [PrintErrorCode]）
     */
    fun print(
        connection: PrinterConnection,
        markdown: String,
        shouldAbort: () -> Boolean = { false },
        onRowsSent: (sentRows: Int, totalRows: Int) -> Unit = { _, _ -> },
    ): PrintOutcome
}

/** PD01 适配器（0x5178 协议，384 点打印宽度） */
class Pd01PrinterAdapter(
    private val energy: Int = Pd01Protocol.DEFAULT_ENERGY,
) : PrinterAdapter {

    override val protocol: PrinterProtocol = PrinterProtocol.PD01

    override fun verify(connection: PrinterConnection) {
        // 状态查询不产生出纸，仅验证链路通畅；固件无响应也不视为致命错误
        //（参考实现同样不等待状态响应，完成确认不能依赖它）
        runCatching {
            connection.writeChunkedBlocking(Pd01Protocol.stateQuery())
        }
    }

    override fun print(
        connection: PrinterConnection,
        markdown: String,
        shouldAbort: () -> Boolean,
        onRowsSent: (sentRows: Int, totalRows: Int) -> Unit,
    ): PrintOutcome {
        val layout = PrintContentRenderer.measure(markdown)
        if (layout.rowCount <= 0) {
            throw PrintException(PrintErrorCode.EMPTY_CONTENT, "打印内容为空")
        }
        val rowFrameBytes = Pd01Protocol.BYTES_PER_LINE + 8  // 行帧 = 帧头 8 字节 + 48 字节点阵

        // 1. 初始化序列（状态查询 / 200DPI / 浓度 / 应用 / LATTICE_START）
        connection.writeChunkedBlocking(mergeFrames(Pd01Protocol.initSequence(energy)), shouldAbort = shouldAbort)

        // 2. 逐条带渲染并点阵发送（同一时刻仅一个条带 Bitmap 存活）
        var rowsSent = 0
        var aborted = false
        PrintContentRenderer.renderStrips(markdown) { strip, _ ->
            val rows = Pd01Protocol.bitmapToRows(strip)
            strip.recycle()
            val packet = mergeFrames(rows.map { Pd01Protocol.printRow(it) })
            val sentBytes = connection.writeChunkedBlocking(packet, shouldAbort = shouldAbort) { sent ->
                val sentRows = minOf(rowsSent + sent / rowFrameBytes, layout.rowCount)
                onRowsSent(sentRows, layout.rowCount)
            }
            val sentRows = sentBytes / rowFrameBytes
            rowsSent += sentRows
            // 块间中止导致发送不满一个条带：停止后续条带
            if (sentBytes < packet.size) {
                aborted = true
                return@renderStrips false
            }
            true
        }

        // 3. 收尾：即便用户中止也尽力发送结束序列，让打印机完成已缓存数据并走纸，
        //    避免固件停留在点阵接收状态；连接已断时静默跳过
        if (aborted || shouldAbort()) {
            runCatching { connection.writeChunkedBlocking(mergeFrames(Pd01Protocol.finishSequence())) }
            throw PrintException(
                PrintErrorCode.CANCELLED_DURING_SEND,
                "打印已中止（已发送 $rowsSent / ${layout.rowCount} 行），可能产生部分出纸"
            )
        }
        connection.writeChunkedBlocking(mergeFrames(Pd01Protocol.finishSequence()), shouldAbort = shouldAbort)
        return PrintOutcome(rowsSent = rowsSent, totalRows = layout.rowCount)
    }

    private fun mergeFrames(frames: List<ByteArray>): ByteArray {
        val total = frames.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }
}
