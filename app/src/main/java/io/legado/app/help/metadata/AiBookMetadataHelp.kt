package io.legado.app.help.metadata

import android.content.Context
import io.legado.app.data.entities.AiBookMetadata
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.APP
import io.legado.app.data.entities.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 书籍元数据辅助类
 * 负责：搜索候选、匹配打分、应用元数据到 custom 字段
 */
object AiBookMetadataHelp {

    /**
     * 搜索候选元数据（使用 App 内置书源搜索，搜索所有启用的书源）
     */
    suspend fun searchCandidates(
        name: String,
        author: String,
        scope: CoroutineScope = Dispatchers.IO
    ): List<MetadataCandidate> {
        val searchKey = if (name.isNotEmpty()) name else author
        if (searchKey.isBlank()) return emptyList()

        val sourceParts = withContext(Dispatchers.IO) {
            APP.getDatabase().bookSourceDao.allEnabledPart
        }
        if (sourceParts.isEmpty()) return emptyList()

        return scope.launch {
            val allResults = sourceParts.mapParallelSafe(AppConfig.threadCount) { part ->
                part.getBookSource()?.let { source ->
                    try {
                        WebBook.searchBookAwait(source, searchKey, 1)
                    } catch (e: Exception) {
                        emptyArrayList<SearchBook>()
                    }
                } ?: emptyArrayList<SearchBook>()
            }.flatten()

            // 去重：同名同作者只保留第一个来源
            val seen = hashSetOf<String>()
            val unique = allResults.filter { sb ->
                val key = "${sb.name}|${sb.author}"
                seen.add(key)
            }

            unique.map { sb ->
                MetadataCandidate(
                    name = sb.name,
                    author = sb.author,
                    kind = sb.kind,
                    intro = sb.intro,
                    coverUrl = sb.coverUrl,
                    authorIntro = null,
                    sourceName = sb.originName,
                    sourceUrl = sb.bookUrl,
                    confidence = 1.0f
                )
            }
        }.join()
    }

    /**
     * 简单匹配打分
     * name 权重最高，其次 author，再 intro 长度/cover 有无
     */
    fun matchCandidates(
        target: Book,
        candidates: List<MetadataCandidate>
    ): List<ScoredCandidate> = candidates.map { candidate ->
        val nameScore = similarity(target.name, candidate.name) * 100
        val authorScore = similarity(target.author, candidate.author) * 50
        val coverScore = if (candidate.coverUrl?.isNotEmpty() == true) 20 else 0
        val introScore = if (candidate.intro?.isNotEmpty() == true) 15 else 0
        val kindScore = if (candidate.kind?.isNotEmpty() == true) 15 else 0

        val total = nameScore + authorScore + coverScore + introScore + kindScore
        ScoredCandidate(candidate, total.coerceIn(0, 100))
    }.sortedByDescending { it.score }

    private fun similarity(a: String?, b: String?): Float {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return 0f
        val aSet = a.lowercase().toSet()
        val bSet = b.lowercase().toSet()
        val intersection = aSet.intersect(bSet).size
        val union = aSet.union(bSet).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    /**
     * 应用元数据到 Book（仅写入 custom 字段，不改动主键字段 name/author）
     * 需在 UI 确认后调用
     */
    suspend fun applyMetadata(
        book: Book,
        candidate: MetadataCandidate,
        onSuccess: (Book) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            val updated = book.apply {
                if (customCoverUrl.isNullOrEmpty() && candidate.coverUrl?.isNotEmpty() == true) {
                    customCoverUrl = candidate.coverUrl
                }
                if (customIntro.isNullOrEmpty() && candidate.intro?.isNotEmpty() == true) {
                    customIntro = candidate.intro
                }
                // kind -> customTag
                if (customTag.isNullOrEmpty() && candidate.kind?.isNotEmpty() == true) {
                    customTag = candidate.kind
                }
                if (customAuthorIntro.isNullOrEmpty() && candidate.authorIntro?.isNotEmpty() == true) {
                    customAuthorIntro = candidate.authorIntro
                }
            }
            // 保存到数据库
            APP.getDatabase().bookDao.updateBook(updated)
            // 记录 AI 匹配结果
            val meta = AiBookMetadata(
                bookUrl = book.bookUrl,
                kind = candidate.kind,
                tags = candidate.kind,
                summary = candidate.intro,
                authorIntro = candidate.authorIntro
            )
            APP.getDatabase().aiBookMetadataDao.insert(meta)
            onSuccess(updated)
        } catch (e: Exception) {
            onError(e)
        }
    }
}

data class MetadataCandidate(
    val name: String,
    val author: String,
    val kind: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val authorIntro: String? = null,
    val sourceName: String,
    val sourceUrl: String,
    val confidence: Float = 1.0f
)

data class ScoredCandidate(
    val candidate: MetadataCandidate,
    val score: Float
)