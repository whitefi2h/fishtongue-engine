package com.fishtongue.lexurgy.desktop

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

const val WORDGEN_PROFILE_VERSION = "wordgen-profile-v1"
const val WORDGEN_ALGORITHM_VERSION = "splitmix64-v1"

@Serializable
data class WeightedSymbol(val value: String, val weight: Int)

@Serializable
data class PhonemeCategory(val name: String, val symbols: List<WeightedSymbol>)

@Serializable
data class WeightedTemplate(val pattern: String, val weight: Int)

@Serializable
data class WeightedSyllableCount(val count: Int, val weight: Int)

@Serializable
data class RewriteRule(val pattern: String, val replacement: String)

@Serializable
data class WordGenerationProfile(
    val categories: List<PhonemeCategory>,
    val templates: List<WeightedTemplate>,
    val syllableCounts: List<WeightedSyllableCount>,
    val forbiddenPatterns: List<String> = emptyList(),
    val rewriteRules: List<RewriteRule> = emptyList(),
    val maxAttemptsPerCandidate: Int = 100,
)

@Serializable
data class WordGenerationValidationIssue(val path: String, val message: String)

@Serializable
data class WordGenerationValidationResult(
    val valid: Boolean,
    val issues: List<WordGenerationValidationIssue>,
)

@Serializable
data class WordGenerationValidateRequest(
    val profileVersion: String,
    val profile: WordGenerationProfile,
)

@Serializable
data class WordGenerationConcept(val conceptKey: String, val gloss: String)

@Serializable
data class WordGenerationRequest(
    val profileVersion: String,
    val profile: WordGenerationProfile,
    val seed: String,
    val concepts: List<WordGenerationConcept>,
    val candidatesPerConcept: Int,
)

@Serializable
data class GeneratedWordCandidate(
    val conceptKey: String,
    val gloss: String,
    val romanized: String,
    val candidateIndex: Int,
)

@Serializable
data class WordGenerationResponse(
    val algorithmVersion: String,
    val profileVersion: String,
    val seed: String,
    val candidates: List<GeneratedWordCandidate>,
)

suspend fun ApplicationCall.validateWordGeneration() {
    val request = receive<WordGenerationValidateRequest>()
    respond(validateProfile(request.profileVersion, request.profile))
}

suspend fun ApplicationCall.generateWords() {
    val request = receive<WordGenerationRequest>()
    val validation = validateProfile(request.profileVersion, request.profile)
    if (!validation.valid) {
        respond(HttpStatusCode.BadRequest, validation)
        return
    }
    if (request.concepts.isEmpty() || request.concepts.size > 500) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "CONCEPT_LIMIT"))
        return
    }
    if (request.candidatesPerConcept !in 1..10 ||
        request.concepts.size * request.candidatesPerConcept > 5_000
    ) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "CANDIDATE_LIMIT"))
        return
    }
    val seed = request.seed.toULongOrNull()
    if (seed == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_SEED"))
        return
    }
    val generator = DeterministicWordGenerator(request.profile, seed)
    val output = mutableListOf<GeneratedWordCandidate>()
    val used = mutableSetOf<String>()
    for (concept in request.concepts) {
        if (concept.gloss.isBlank()) {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_GLOSS"))
            return
        }
        repeat(request.candidatesPerConcept) { index ->
            val word = generator.nextUniqueWord(used)
            if (word == null) {
                respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "NO_LEGAL_CANDIDATE"))
                return
            }
            output += GeneratedWordCandidate(
                conceptKey = concept.conceptKey,
                gloss = concept.gloss,
                romanized = word,
                candidateIndex = index,
            )
        }
    }
    respond(
        WordGenerationResponse(
            algorithmVersion = WORDGEN_ALGORITHM_VERSION,
            profileVersion = request.profileVersion,
            seed = request.seed,
            candidates = output,
        )
    )
}

fun validateProfile(
    profileVersion: String,
    profile: WordGenerationProfile,
): WordGenerationValidationResult {
    val issues = mutableListOf<WordGenerationValidationIssue>()
    if (profileVersion != WORDGEN_PROFILE_VERSION) {
        issues += issue("profileVersion", "不支持的造词配置版本。")
    }
    if (profile.categories.isEmpty()) issues += issue("categories", "至少需要一个音位类别。")
    val categoryNames = mutableSetOf<String>()
    profile.categories.forEachIndexed { categoryIndex, category ->
        val path = "categories[$categoryIndex]"
        if (category.name.isBlank()) issues += issue("$path.name", "类别名称不能为空。")
        if (!categoryNames.add(category.name)) issues += issue("$path.name", "类别名称不能重复。")
        if (category.symbols.isEmpty()) issues += issue("$path.symbols", "类别至少需要一个符号。")
        category.symbols.forEachIndexed { symbolIndex, symbol ->
            if (symbol.value.isEmpty()) {
                issues += issue("$path.symbols[$symbolIndex].value", "符号不能为空。")
            }
            if (symbol.weight <= 0) {
                issues += issue("$path.symbols[$symbolIndex].weight", "权重必须大于零。")
            }
        }
    }
    if (profile.templates.isEmpty()) issues += issue("templates", "至少需要一个音节模板。")
    profile.templates.forEachIndexed { index, template ->
        if (template.pattern.isBlank()) issues += issue("templates[$index].pattern", "模板不能为空。")
        if (template.weight <= 0) issues += issue("templates[$index].weight", "权重必须大于零。")
        categoryReferences(template.pattern).forEach { reference ->
            if (reference !in categoryNames) {
                issues += issue("templates[$index].pattern", "模板引用了不存在的类别 {$reference}。")
            }
        }
    }
    if (profile.syllableCounts.isEmpty()) {
        issues += issue("syllableCounts", "至少需要一种音节数量。")
    }
    profile.syllableCounts.forEachIndexed { index, count ->
        if (count.count !in 1..12) {
            issues += issue("syllableCounts[$index].count", "音节数量必须在 1 到 12 之间。")
        }
        if (count.weight <= 0) {
            issues += issue("syllableCounts[$index].weight", "权重必须大于零。")
        }
    }
    profile.forbiddenPatterns.forEachIndexed { index, pattern ->
        regexIssue(pattern)?.let { issues += issue("forbiddenPatterns[$index]", it) }
    }
    profile.rewriteRules.forEachIndexed { index, rule ->
        regexIssue(rule.pattern)?.let { issues += issue("rewriteRules[$index].pattern", it) }
    }
    if (profile.maxAttemptsPerCandidate !in 1..10_000) {
        issues += issue("maxAttemptsPerCandidate", "单个候选最大尝试次数必须在 1 到 10000 之间。")
    }
    return WordGenerationValidationResult(issues.isEmpty(), issues)
}

private fun issue(path: String, message: String) = WordGenerationValidationIssue(path, message)

private fun regexIssue(pattern: String): String? =
    try {
        Regex(pattern)
        null
    } catch (_: IllegalArgumentException) {
        "正则表达式无效。"
    }

private val categoryPattern = Regex("""\{([^{}]+)}""")

private fun categoryReferences(pattern: String): List<String> =
    categoryPattern.findAll(pattern).map { it.groupValues[1] }.toList()

private class DeterministicWordGenerator(
    private val profile: WordGenerationProfile,
    seed: ULong,
) {
    private val random = SplitMix64(seed)
    private val categories = profile.categories.associateBy { it.name }
    private val forbidden = profile.forbiddenPatterns.map(::Regex)
    private val rewrites = profile.rewriteRules.map { Regex(it.pattern) to it.replacement }

    fun nextUniqueWord(used: MutableSet<String>): String? {
        repeat(profile.maxAttemptsPerCandidate) {
            var word = buildString {
                val syllableCount = choose(profile.syllableCounts) { it.weight }.count
                repeat(syllableCount) {
                    append(renderTemplate(choose(profile.templates) { it.weight }.pattern))
                }
            }
            rewrites.forEach { (pattern, replacement) ->
                word = pattern.replace(word, replacement)
            }
            if (word.isNotBlank() && forbidden.none { it.containsMatchIn(word) } && used.add(word)) {
                return word
            }
        }
        return null
    }

    private fun renderTemplate(pattern: String): String {
        val result = StringBuilder()
        var cursor = 0
        for (match in categoryPattern.findAll(pattern)) {
            result.append(pattern.substring(cursor, match.range.first))
            val category = categories.getValue(match.groupValues[1])
            result.append(choose(category.symbols) { it.weight }.value)
            cursor = match.range.last + 1
        }
        result.append(pattern.substring(cursor))
        return result.toString()
    }

    private fun <T> choose(values: List<T>, weight: (T) -> Int): T {
        val total = values.sumOf { weight(it).toLong() }
        var cursor = random.nextBounded(total.toULong()).toLong()
        for (value in values) {
            cursor -= weight(value)
            if (cursor < 0) return value
        }
        return values.last()
    }
}

internal class SplitMix64(seed: ULong) {
    private var state = seed

    fun nextULong(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var value = state
        value = (value xor (value shr 30)) * 0xBF58476D1CE4E5B9uL
        value = (value xor (value shr 27)) * 0x94D049BB133111EBuL
        return value xor (value shr 31)
    }

    fun nextBounded(bound: ULong): ULong {
        require(bound > 0uL)
        return nextULong() % bound
    }
}
