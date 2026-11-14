package me.rerere.rikkahub.common.utils

import android.util.Log
import me.rerere.rikkahub.common.BuildConfig

/**
 * Logging utility that gates debug logs behind BuildConfig.DEBUG.
 *
 * In release builds:
 * - d() and i() are no-ops (no debug/info logs)
 * - w() and e() still log (warnings and errors are important)
 *
 * Usage: Replace Log.d(TAG, msg) with LogUtil.d(TAG, msg)
 */
object LogUtil {
    /**
     * Debug log - only in debug builds.
     * 注意：lambda 变体刻意**不**加 inline——ai 模块 JVM target(11) 低于 common(17)，
     * 跨模块内联会报 "Cannot inline bytecode built with JVM target 17" 编译错误；
     * 非 inline 的 lambda 参数保留惰性求值（release 下不构建消息字符串）。
     */
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    /**
     * Debug log - only in debug builds (simple string version)
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * Info log - only in debug builds
     */
    fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message())
        }
    }

    /**
     * Info log - only in debug builds (simple string version)
     */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }

    /**
     * Warning log - always logs (warnings are important)
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /**
     * Warning log with exception - always logs
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
    }

    /**
     * Error log - always logs (errors are critical)
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    /**
     * Error log with exception - always logs
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }
}
