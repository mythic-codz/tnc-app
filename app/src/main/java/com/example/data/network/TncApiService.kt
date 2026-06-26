package com.example.data.network

import com.example.data.model.CrmExamRow
import com.example.data.model.CrmQuestionRow
import com.example.data.model.SupabaseAttemptRequest
import com.example.data.model.TncPayload
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface TncCrmService {
    @POST("common/")
    @Headers("Content-Type: application/json")
    suspend fun getExams(
        @Body payload: TncPayload
    ): List<CrmExamRow>

    @POST("common/")
    @Headers("Content-Type: application/json")
    suspend fun getQuestions(
        @Body payload: TncPayload
    ): List<CrmQuestionRow>
}

interface SupabaseService {
    @POST("rest/v1/quiz_attempts")
    @Headers("Content-Type: application/json", "Prefer: return=minimal")
    suspend fun saveAttempt(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authHeader: String,
        @Body attempt: SupabaseAttemptRequest
    )
}
