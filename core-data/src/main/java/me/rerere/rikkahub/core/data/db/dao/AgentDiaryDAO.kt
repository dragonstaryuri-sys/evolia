package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryCommentEntity

@Dao
interface AgentDiaryDAO {
    // --- 日记基础操作 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiary(diary: AgentDiaryEntity)

    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id = :assistantId ORDER BY date DESC")
    fun getDiariesByAssistant(assistantId: String): Flow<List<AgentDiaryEntity>>

    @Query("SELECT * FROM AgentDiaryEntity ORDER BY date DESC")
    fun getAllDiaries(): Flow<List<AgentDiaryEntity>>

    @Query("SELECT * FROM AgentDiaryEntity WHERE id = :id LIMIT 1")
    suspend fun getDiaryById(id: String): AgentDiaryEntity?

    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id = :assistantId AND date = :date LIMIT 1")
    suspend fun getDiaryByDate(assistantId: String, date: String): AgentDiaryEntity?

    @Query("DELETE FROM AgentDiaryEntity WHERE id = :id")
    suspend fun deleteDiaryById(id: String)

    @Query("DELETE FROM AgentDiaryEntity WHERE assistant_id = :assistantId")
    suspend fun deleteDiariesByAssistant(assistantId: String)

    // 核心修复：补回获取最后一条日记的方法
    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastDiaryOfAssistant(assistantId: String): AgentDiaryEntity?

    // --- 升级需求：人员筛选 ---
    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id IN (:assistantIds) ORDER BY date DESC")
    fun getDiariesByAssistants(assistantIds: List<String>): Flow<List<AgentDiaryEntity>>

    // --- 升级需求：日历红点 (获取有日记的所有日期) ---
    @Query("SELECT DISTINCT date FROM AgentDiaryEntity WHERE assistant_id IN (:assistantIds)")
    fun getDatesWithDiaries(assistantIds: List<String>): Flow<List<String>>

    @Query("SELECT * FROM AgentDiaryEntity WHERE date = :date AND assistant_id IN (:assistantIds) ORDER BY created_at DESC")
    fun getDiariesByDateAndAssistants(date: String, assistantIds: List<String>): Flow<List<AgentDiaryEntity>>

    // --- 升级需求：评论功能 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: DiaryCommentEntity)

    @Query("SELECT * FROM DiaryCommentEntity WHERE diary_id = :diaryId ORDER BY created_at ASC")
    fun getCommentsForDiary(diaryId: String): Flow<List<DiaryCommentEntity>>

    @Query("DELETE FROM DiaryCommentEntity WHERE id = :commentId")
    suspend fun deleteComment(commentId: String)
}
