package me.rerere.rikkahub.core.data.db

/**
 * 数据库事务执行器抽象。
 *
 * Room 的 withTransaction 需要持有 RoomDatabase 实例，而该实例定义在 app 模块；
 * 各库模块（如 discover）的 Repository 只能拿到 DAO，无法直接开启跨 DAO 事务。
 * 通过此接口由 app 模块提供基于 `RoomDatabase.withTransaction` 的实现，并经 DI 注入，
 * 使"创建待办 + 创建关联日程"这类多步写入保持在同一事务内，避免留下半成品数据。
 */
interface DbTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}
