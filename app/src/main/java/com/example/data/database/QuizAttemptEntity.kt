package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quiz_attempts")
data class QuizAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: String,
    val examName: String,
    val userId: String,
    val userName: String,
    val answersJson: String, // JSON representation of rowId -> option Map
    val score: Double,
    val totalMarks: Double,
    val correctCount: Int,
    val wrongCount: Int,
    val skippedCount: Int,
    val timeTakenSeconds: Int,
    val submittedAt: Long
)
