package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- CRM API Payload ---
@JsonClass(generateAdapter = true)
data class TncPayload(
    @Json(name = "payload") val payload: String
)

@JsonClass(generateAdapter = true)
data class CrmQuery(
    @Json(name = "fn") val fn: String = "common_fn",
    @Json(name = "se") val se: String = "fe",
    @Json(name = "sch") val sch: String,
    @Json(name = "data") val data: Map<String, String>,
    @Json(name = "cond") val cond: Map<String, String>
)

// --- CRM Exam Response Models ---
@JsonClass(generateAdapter = true)
data class CrmExamRow(
    @Json(name = "row_id") val rowId: String,
    @Json(name = "examno") val examNo: Int?,
    @Json(name = "qu_refid") val quRefid: List<String>?,
    @Json(name = "cr_on") val crOn: String?,
    @Json(name = "json") val json: CrmExamJson?
)

@JsonClass(generateAdapter = true)
data class CrmExamJson(
    @Json(name = "_ex_na") val examName: String?,
    @Json(name = "_ma_ma") val maxMarks: Double?,
    @Json(name = "_ne_ma") val negativeMarks: Double?,
    @Json(name = "_ex_du") val durationMinutes: String?,
    @Json(name = "_al_fo_pr") val allowForPremium: Int? // 1 = true
)

// --- CRM Question Response Models ---
@JsonClass(generateAdapter = true)
data class CrmQuestionRow(
    @Json(name = "row_id") val rowId: String,
    @Json(name = "json") val json: CrmQuestionJson?
)

@JsonClass(generateAdapter = true)
data class CrmQuestionJson(
    @Json(name = "_qno") val qNo: Int?,
    @Json(name = "_qu") val quObj: CrmQuestionTextObj?,
    @Json(name = "_op") val optionsObj: CrmQuestionOptionsObj?,
    @Json(name = "_an") val answer: String?, // "A", "B", "C", "D"
    @Json(name = "_so") val solutionObj: CrmQuestionSolutionObj?
)

@JsonClass(generateAdapter = true)
data class CrmQuestionTextObj(
    @Json(name = "_qu") val questionText: String?,
    @Json(name = "_im") val imageObj: CrmQuestionImageObj?
)

@JsonClass(generateAdapter = true)
data class CrmQuestionImageObj(
    @Json(name = "_li") val imagePath: String?
)

@JsonClass(generateAdapter = true)
data class CrmQuestionOptionsObj(
    @Json(name = "_op_A") val opA: CrmOptionItem?,
    @Json(name = "_op_B") val opB: CrmOptionItem?,
    @Json(name = "_op_C") val opC: CrmOptionItem?,
    @Json(name = "_op_D") val opD: CrmOptionItem?
)

@JsonClass(generateAdapter = true)
data class CrmOptionItem(
    @Json(name = "_op_ti") val optionText: String?
)

@JsonClass(generateAdapter = true)
data class CrmQuestionSolutionObj(
    @Json(name = "_ti") val explanation: String?
)

// --- Parsed App-Friendly Models ---
data class QuizExam(
    val examId: String,
    val examNo: Int,
    val name: String,
    val maxMarks: Double,
    val negativeMarks: Double,
    val durationMinutes: String,
    val questionCount: Int,
    val allowForPremium: Boolean,
    val createdAt: String?
)

data class QuizQuestion(
    val rowId: String,
    val questionNo: Int?,
    val questionText: String,
    val imageUrl: String?,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctAnswer: String,
    val explanation: String?
)

data class ExamDetail(
    val exam: QuizExam,
    val questions: List<QuizQuestion>
)

// --- Supabase Attempt Request Model ---
@JsonClass(generateAdapter = true)
data class SupabaseAttemptRequest(
    @Json(name = "exam_id") val examId: String,
    @Json(name = "exam_name") val examName: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "user_name") val userName: String,
    @Json(name = "answers") val answers: Map<String, String>, // rowId -> chosen option
    @Json(name = "score") val score: Double,
    @Json(name = "total_marks") val totalMarks: Double,
    @Json(name = "correct_count") val correctCount: Int,
    @Json(name = "wrong_count") val wrongCount: Int,
    @Json(name = "skipped_count") val skippedCount: Int,
    @Json(name = "time_taken_seconds") val timeTakenSeconds: Int,
    @Json(name = "submitted_at") val submittedAt: String
)
