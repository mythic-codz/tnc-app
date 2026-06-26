package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.QuizAttemptEntity
import com.example.data.model.ExamDetail
import com.example.data.model.QuizExam
import com.example.data.model.QuizQuestion
import com.example.data.repository.TncRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ExamsUiState {
    object Loading : ExamsUiState
    data class Success(val exams: List<QuizExam>, val totalCount: Int) : ExamsUiState
    data class Error(val message: String) : ExamsUiState
}

sealed interface ExamDetailUiState {
    object Idle : ExamDetailUiState
    object Loading : ExamDetailUiState
    data class Success(val detail: ExamDetail) : ExamDetailUiState
    data class Error(val message: String) : ExamDetailUiState
}

class TncViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TncRepository(application)

    // --- Pop-up Promo State ---
    var showPromoPopup by mutableStateOf(false)
        private set

    companion object {
        // survive configuration changes and screen transitions within the session
        private var isPopupDismissedInSession = false
    }

    // --- Search & Filtering States ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory = _selectedCategory.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage = _currentPage.asStateFlow()

    private val _examsUiState = MutableStateFlow<ExamsUiState>(ExamsUiState.Loading)
    val examsUiState: StateFlow<ExamsUiState> = _examsUiState.asStateFlow()

    // Dynamic counts computed across all loaded exams in the repository's cache
    private val _categoryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val categoryCounts = _categoryCounts.asStateFlow()

    // --- Quiz Taking States ---
    private val _examDetailUiState = MutableStateFlow<ExamDetailUiState>(ExamDetailUiState.Idle)
    val examDetailUiState: StateFlow<ExamDetailUiState> = _examDetailUiState.asStateFlow()

    // Active Quiz Phase variables
    var currentQuestionIndex by mutableStateOf(0)
    var selectedAnswers by mutableStateOf<Map<String, String>>(emptyMap()) // rowId -> "A"/"B"/"C"/"D"
    var secondsRemaining by mutableStateOf(0)
    var isQuizActive by mutableStateOf(false)
    var quizCompleted by mutableStateOf(false)

    // Saved result variables
    var activeScore by mutableStateOf(0.0)
    var activeCorrectCount by mutableStateOf(0)
    var activeWrongCount by mutableStateOf(0)
    var activeSkippedCount by mutableStateOf(0)
    var isSavingAttempt by mutableStateOf(false)
    var saveSuccess by mutableStateOf(false)

    private var timerJob: Job? = null

    // --- Local Attempts Statistics (Room Database Flow) ---
    val localAttempts: StateFlow<List<QuizAttemptEntity>> = repository.getAllLocalAttempts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        triggerPromoPopupDelayed()
        loadExams()
    }

    private fun triggerPromoPopupDelayed() {
        if (isPopupDismissedInSession) return
        viewModelScope.launch {
            delay(2000)
            if (!isPopupDismissedInSession) {
                showPromoPopup = true
            }
        }
    }

    fun dismissPromoPopup() {
        showPromoPopup = false
        isPopupDismissedInSession = true
    }

    fun searchExams(query: String) {
        _searchQuery.value = query
        _currentPage.value = 1
        loadExams()
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        _currentPage.value = 1
        loadExams()
    }

    fun setPage(page: Int) {
        _currentPage.value = page
        loadExams()
    }

    fun loadExams(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _examsUiState.value = ExamsUiState.Loading
            try {
                // First fetch all matches to determine category counts
                val (allExams, total) = repository.getExams(page = 1, limit = 10000, forceRefresh = forceRefresh)
                
                // Perform local classifications to compute counts
                val counts = mutableMapOf<String, Int>()
                allExams.forEach { exam ->
                    val cat = getCategory(exam.name)
                    counts[cat] = (counts[cat] ?: 0) + 1
                }
                counts["All"] = allExams.size
                _categoryCounts.value = counts

                // Filter matching query & category
                val filtered = allExams.filter { exam ->
                    val matchesQuery = exam.name.contains(_searchQuery.value, ignoreCase = true)
                    val matchesCategory = _selectedCategory.value == "All" || getCategory(exam.name) == _selectedCategory.value
                    matchesQuery && matchesCategory
                }

                val totalFiltered = filtered.size
                val limit = 20
                val pageIndex = _currentPage.value
                val start = (pageIndex - 1) * limit
                val paginated = if (start < totalFiltered) {
                    val end = (start + limit).coerceAtMost(totalFiltered)
                    filtered.subList(start, end)
                } else emptyList()

                _examsUiState.value = ExamsUiState.Success(paginated, totalFiltered)
            } catch (e: Exception) {
                _examsUiState.value = ExamsUiState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }

    fun loadExamDetail(examId: String) {
        viewModelScope.launch {
            _examDetailUiState.value = ExamDetailUiState.Loading
            try {
                val detail = repository.getExamDetail(examId)
                if (detail != null) {
                    _examDetailUiState.value = ExamDetailUiState.Success(detail)
                } else {
                    _examDetailUiState.value = ExamDetailUiState.Error("Exam details not found")
                }
            } catch (e: Exception) {
                _examDetailUiState.value = ExamDetailUiState.Error(e.message ?: "Failed to load exam")
            }
        }
    }

    fun startQuiz(durationMinutes: String) {
        currentQuestionIndex = 0
        selectedAnswers = emptyMap()
        val minutes = durationMinutes.toIntOrNull() ?: 90
        secondsRemaining = minutes * 60
        isQuizActive = true
        quizCompleted = false
        saveSuccess = false

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (secondsRemaining > 0 && isQuizActive) {
                delay(1000)
                secondsRemaining--
            }
            if (secondsRemaining <= 0 && isQuizActive) {
                submitQuiz()
            }
        }
    }

    fun selectOption(questionId: String, option: String) {
        val updated = selectedAnswers.toMutableMap()
        updated[questionId] = option
        selectedAnswers = updated
    }

    fun submitQuiz() {
        val state = _examDetailUiState.value
        if (state !is ExamDetailUiState.Success) return
        
        isQuizActive = false
        timerJob?.cancel()

        val exam = state.detail.exam
        val questions = state.detail.questions

        // Marks calculation logic
        val marksPerQ = exam.maxMarks / questions.size.coerceAtLeast(1)
        var correct = 0
        var wrong = 0
        var skipped = 0

        for (q in questions) {
            val ans = selectedAnswers[q.rowId]
            if (ans == null) {
                skipped++
            } else if (ans == q.correctAnswer) {
                correct++
            } else {
                wrong++
            }
        }

        // Final score = correct * marksPerQ - wrong * negativeMarks
        val score = (correct * marksPerQ) - (wrong * exam.negativeMarks)
        activeScore = score.coerceAtLeast(0.0)
        activeCorrectCount = correct
        activeWrongCount = wrong
        activeSkippedCount = skipped
        quizCompleted = true

        // Async save attempt to local DB (Room) and remote (Supabase)
        viewModelScope.launch {
            isSavingAttempt = true
            val success = repository.saveAttempt(
                examId = exam.examId,
                examName = exam.name,
                answers = selectedAnswers,
                score = activeScore,
                totalMarks = exam.maxMarks,
                correctCount = correct,
                wrongCount = wrong,
                skippedCount = skipped,
                timeTakenSeconds = (exam.durationMinutes.toIntOrNull() ?: 90) * 60 - secondsRemaining
            )
            saveSuccess = success
            isSavingAttempt = false
        }
    }

    fun getGrade(score: Double, totalMarks: Double): String {
        if (totalMarks <= 0) return "Keep Practicing"
        val pct = (score / totalMarks) * 100
        return when {
            pct >= 80.0 -> "Excellent"
            pct >= 60.0 -> "Good"
            pct >= 40.0 -> "Average"
            else -> "Keep Practicing"
        }
    }

    fun resetQuizState() {
        _examDetailUiState.value = ExamDetailUiState.Idle
        isQuizActive = false
        quizCompleted = false
        selectedAnswers = emptyMap()
        currentQuestionIndex = 0
        timerJob?.cancel()
    }

    fun getCategory(name: String): String {
        val n = name.uppercase()
        return when {
            n.contains("NORCET") -> "NORCET"
            n.contains("AIIMS") -> "AIIMS"
            n.contains("SGPGI") -> "SGPGI"
            n.contains("BTSC") -> "BTSC"
            n.contains("CHO") -> "CHO"
            n.contains("CHN") -> "CHN"
            n.contains("OT ") || n.contains("THEATRE") -> "OT"
            n.contains("MORNING") || n.contains("DOSE") -> "Daily Dose"
            else -> "Other"
        }
    }
}
