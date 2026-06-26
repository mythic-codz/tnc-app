package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.database.AppDatabase
import com.example.data.database.QuizAttemptEntity
import com.example.data.model.*
import com.example.data.network.SupabaseService
import com.example.data.network.TncCrmService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class TncRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val quizAttemptDao = db.quizAttemptDao()

    // Configured Moshi with Kotlin Reflection support
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Custom OkHttpClient configured for high concurrency parallel fetching
    private val okHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 120
            maxRequestsPerHost = 60
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    // Service endpoints
    private val crmBaseUrl = try { BuildConfig.TNC_CRM_BASE } catch (e: Exception) { "https://crm.tncnursing.in" }
    private val supabaseUrl = try { BuildConfig.TNC_SUPABASE_URL } catch (e: Exception) { "https://romnohcmbtsgrhyggwbg.supabase.co" }

    private val crmRetrofit = Retrofit.Builder()
        .baseUrl(if (crmBaseUrl.endsWith("/")) crmBaseUrl else "$crmBaseUrl/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val supabaseRetrofit = Retrofit.Builder()
        .baseUrl(if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val crmService = crmRetrofit.create(TncCrmService::class.java)
    private val supabaseService = supabaseRetrofit.create(SupabaseService::class.java)

    // Moshi Adapters for encoding/decoding dynamic JSON payloads
    private val queryAdapter = moshi.adapter(CrmQuery::class.java)
    private val answersMapAdapter = moshi.adapter<Map<String, String>>(
        com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    // In-memory cache for loaded tests
    private var cachedExamsList: List<QuizExam>? = null

    /**
     * Fetches and filters the list of exams/quizzes from the CRM.
     * Caches the full result in-memory for immediate paging, filtering and sorting.
     */
    suspend fun getExams(page: Int, limit: Int, forceRefresh: Boolean = false): Pair<List<QuizExam>, Int> = withContext(Dispatchers.IO) {
        try {
            var exams = cachedExamsList
            if (exams == null || forceRefresh) {
                val queryObj = CrmQuery(
                    sch = "t_ex",
                    data = mapOf("json" to "*", "qu_refid" to "*", "examno" to "*"),
                    cond = emptyMap()
                )
                val stringPayload = queryAdapter.toJson(queryObj)
                val rawRows = crmService.getExams(TncPayload(stringPayload))

                exams = rawRows
                    .filter { (it.quRefid?.size ?: 0) > 0 } // only exams with questions
                    .map { parseExamRow(it) }
                    .sortedByDescending { it.examNo } // highest examNo first
                
                cachedExamsList = exams
            }

            val total = exams.size
            val start = (page - 1) * limit
            if (start >= total) {
                return@withContext Pair(emptyList(), total)
            }
            val end = (start + limit).coerceAtMost(total)
            val paginatedList = exams.subList(start, end)
            Pair(paginatedList, total)
        } catch (e: Exception) {
            Log.e("TncRepository", "Error fetching exams: ${e.message}", e)
            Pair(emptyList(), 0)
        }
    }

    /**
     * Fetches detailed information of an exam including all its questions fetched concurrently.
     */
    suspend fun getExamDetail(examId: String): ExamDetail? = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch metadata & question reference IDs
            val queryObj = CrmQuery(
                sch = "t_ex",
                data = mapOf("json" to "*", "qu_refid" to "*"),
                cond = mapOf("row_id" to examId)
            )
            val stringPayload = queryAdapter.toJson(queryObj)
            val rows = crmService.getExams(TncPayload(stringPayload))
            if (rows.isEmpty()) return@withContext null

            val row = rows[0]
            val exam = parseExamRow(row)
            val quRefids = row.quRefid ?: emptyList()

            // 2. Fetch all questions in parallel utilizing OkHttp concurrent thread dispatcher
            val deferredQuestions = quRefids.map { rowId ->
                async {
                    try {
                        val qQueryObj = CrmQuery(
                            sch = "t_qu",
                            data = mapOf("json" to "*"),
                            cond = mapOf("row_id" to rowId)
                        )
                        val qStringPayload = queryAdapter.toJson(qQueryObj)
                        val qRows = crmService.getQuestions(TncPayload(qStringPayload))
                        if (qRows.isNotEmpty()) {
                            parseQuestionRow(qRows[0])
                        } else null
                    } catch (e: Exception) {
                        Log.e("TncRepository", "Failed to fetch question $rowId: ${e.message}")
                        null
                    }
                }
            }

            val questions = deferredQuestions.awaitAll()
                .filterNotNull()
                .filter { it.questionText.trim().isNotEmpty() }
                .sortedBy { it.questionNo ?: 0 }

            ExamDetail(exam, questions)
        } catch (e: Exception) {
            Log.e("TncRepository", "Error fetching exam details for $examId: ${e.message}", e)
            null
        }
    }

    /**
     * Saves attempt results both to the local Room DB and the remote Supabase DB.
     */
    suspend fun saveAttempt(
        examId: String,
        examName: String,
        userId: String = "guest",
        userName: String = "Guest",
        answers: Map<String, String>,
        score: Double,
        totalMarks: Double,
        correctCount: Int,
        wrongCount: Int,
        skippedCount: Int,
        timeTakenSeconds: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val answersJson = answersMapAdapter.toJson(answers)
        val epochTime = System.currentTimeMillis()

        // 1. Persist locally in Room (Guaranteed local offline backup)
        val localEntity = QuizAttemptEntity(
            examId = examId,
            examName = examName,
            userId = userId,
            userName = userName,
            answersJson = answersJson,
            score = score,
            totalMarks = totalMarks,
            correctCount = correctCount,
            wrongCount = wrongCount,
            skippedCount = skippedCount,
            timeTakenSeconds = timeTakenSeconds,
            submittedAt = epochTime
        )
        try {
            quizAttemptDao.insertAttempt(localEntity)
            Log.d("TncRepository", "Attempt successfully saved to local Room DB")
        } catch (dbEx: Exception) {
            Log.e("TncRepository", "Room DB insert failure: ${dbEx.message}", dbEx)
        }

        // 2. Post to Supabase DB (Remote persistence)
        val supabaseKey = try { BuildConfig.TNC_SUPABASE_ANON_KEY } catch (e: Exception) { "" }
        if (supabaseKey.isEmpty() || supabaseKey == "your_supabase_anon_key_here") {
            Log.w("TncRepository", "Supabase key is empty/default, skipping remote save")
            return@withContext false
        }

        try {
            val isoDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(epochTime))

            val supabaseRequest = SupabaseAttemptRequest(
                examId = examId,
                examName = examName,
                userId = userId,
                userName = userName,
                answers = answers,
                score = score,
                totalMarks = totalMarks,
                correctCount = correctCount,
                wrongCount = wrongCount,
                skippedCount = skippedCount,
                timeTakenSeconds = timeTakenSeconds,
                submittedAt = isoDate
            )

            supabaseService.saveAttempt(
                apiKey = supabaseKey,
                authHeader = "Bearer $supabaseKey",
                attempt = supabaseRequest
            )
            Log.d("TncRepository", "Attempt successfully uploaded to Supabase")
            true
        } catch (supabaseEx: Exception) {
            Log.e("TncRepository", "Supabase sync failure: ${supabaseEx.message}", supabaseEx)
            false
        }
    }

    /**
     * Read the local attempts Flow.
     */
    fun getAllLocalAttempts(): Flow<List<QuizAttemptEntity>> = quizAttemptDao.getAllAttempts()

    // --- Private Parsing Utilities ---

    private fun parseExamRow(row: CrmExamRow): QuizExam {
        val json = row.json
        return QuizExam(
            examId = row.rowId,
            examNo = row.examNo ?: 0,
            name = json?.examName ?: "Quiz",
            maxMarks = json?.maxMarks ?: 0.0,
            negativeMarks = json?.negativeMarks ?: 0.33,
            durationMinutes = json?.durationMinutes ?: "90",
            questionCount = row.quRefid?.size ?: 0,
            allowForPremium = json?.allowForPremium == 1,
            createdAt = row.crOn
        )
    }

    private fun parseQuestionRow(row: CrmQuestionRow): QuizQuestion {
        val json = row.json
        val quObj = json?.quObj
        val ops = json?.optionsObj
        val solutionObj = json?.solutionObj
        val imgPath = quObj?.imageObj?.imagePath

        return QuizQuestion(
            rowId = row.rowId,
            questionNo = json?.qNo,
            questionText = quObj?.questionText ?: "",
            imageUrl = buildMediaUrl(imgPath),
            optionA = ops?.opA?.optionText ?: "",
            optionB = ops?.opB?.optionText ?: "",
            optionC = ops?.opC?.optionText ?: "",
            optionD = ops?.opD?.optionText ?: "",
            correctAnswer = json?.answer ?: "",
            explanation = solutionObj?.explanation
        )
    }

    private fun buildMediaUrl(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("http")) return path
        val cleanPath = path.removePrefix("/")
        return "$crmBaseUrl/$cleanPath"
    }
}
