package me.rerere.rikkahub.data.db

import androidx.room.withTransaction
import me.rerere.rikkahub.core.data.db.DbTransactionRunner

/**
 * 基于 Room withTransaction 的事务执行器实现。
 * 供 discover 等库模块的 Repository 经 DI 注入使用，
 * 保证"新建待办 + 新建关联日程"等多步写入的原子性。
 */
class RoomTransactionRunner(
    private val db: AppDatabase
) : DbTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return db.withTransaction { block() }
    }
}
