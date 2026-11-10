package io.legado.app.help.metadata

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiBookMetadata
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.data.entities.Book
import io.legado.app.constant.AppLog
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 书籍元数据辅助类
 * 负责：搜索候选、匹配打分、应用元数据到 custom 字段
 */
object AiBookMetadataHelp {

    /**
     * 批量搜索候选元数据（使用 App 内置书源搜索，搜索所有启用的书源）
     * 只返回至少有一个字段为空的书籍
     */
    suspend fun searchBatchCandidates(
        books: Set<Book>
    ): List<Pair<Book, MetadataCandidate>> = coroutineScope {
        val targets = books.filter {
            it.customCoverUrl.isNullOrEmpty() ||
                it.customIntro.isNullOrEmpty() ||
                it.customTag.isNullOrEmpty()
        }
        if (targets.isEmpty()) return@coroutineScope emptyList()

        val sourceParts = withContext(Dispatchers.IO) {
            appDb.bookSourceDao.allEnabledPart
        }
        if (sourceParts.isEmpty()) return@coroutineScope emptyList()

        val results = mutableListOf<Pair<Book, MetadataCandidate>>()
        for (book in targets) {
            val searchKey = if (book.name.isNotEmpty()) book.name else book.author
            if (searchKey.isBlank()) continue
            val candidates = flow {
                for (bs in sourceParts) {
                    bs.getBookSource()?.let { emit(it) }
                }
            }.mapParallelSafe(AppConfig.threadCount) { source ->
                try {
                    WebBook.searchBookAwait(source, searchKey, 1)
                } catch (e: Exception) {
                    AppLog.put("批量元数据搜索出错: ${e.message}", e)
                    emptyList<SearchBook>()
                }
            }.toList().flatten()

            // 去重：同名同作者只保留第一个来源
            val seen = hashSetOf<String>()
            val unique = candidates.filter { sb ->
                seen.add("${sb.name}|${sb.author}")
            }

            if (unique.isNotEmpty()) {
                val sb = unique[0]
                results.add(book to MetadataCandidate(
                    name = sb.name,
                    author = sb.author,
                    kind = sb.kind,
                    intro = sb.intro,
                    coverUrl = sb.coverUrl,
                    authorIntro = null,
                    sourceName = sb.originName,
                    sourceUrl = sb.bookUrl,
                    confidence = 1.0f
                ))
            }
        }
        results
    }
    suspend fun searchCandidates(
        name: String,
        author: String
    ): List<MetadataCandidate> = coroutineScope {
        val searchKey = if (name.isNotEmpty()) name else author
        if (searchKey.isBlank()) return@coroutineScope emptyList()

        val sourceParts = withContext(Dispatchers.IO) {
            appDb.bookSourceDao.allEnabledPart
        }
        if (sourceParts.isEmpty()) return@coroutineScope emptyList()

        val allResults: List<SearchBook> = flow {
            for (bs in sourceParts) {
                bs.getBookSource()?.let { emit(it) }
            }
        }.mapParallelSafe(AppConfig.threadCount) { source ->
            try {
                WebBook.searchBookAwait(source, searchKey, 1)
            } catch (e: Exception) {
                AppLog.put("AI元数据搜索出错: ${e.message}", e)
                emptyList<SearchBook>()
            }
        }.toList().flatten()

        // 去重：同名同作者只保留第一个来源
        val seen = hashSetOf<String>()
        val unique = allResults.filter { sb ->
            seen.add("${sb.name}|${sb.author}")
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
    }

    /**
     * 简单匹配打分
     * name 权重最高，其次 author，再 intro 长度/cover 有无
     */
    fun matchCandidates(
        target: Book,
        candidates: List<MetadataCandidate>
    ): List<ScoredCandidate> = candidates.map { candidate ->
        val nameScore = (similarity(target.name, candidate.name) * 100).toInt()
        val authorScore = (similarity(target.author, candidate.author) * 50).toInt()
        val coverScore = if (candidate.coverUrl?.isNotEmpty() == true) 20 else 0
        val introScore = if (candidate.intro?.isNotEmpty() == true) 15 else 0
        val kindScore = if (candidate.kind?.isNotEmpty() == true) 15 else 0

        val total = nameScore + authorScore + coverScore + introScore + kindScore
        ScoredCandidate(candidate, total.coerceIn(0, 100).toFloat())
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
            }
            // 保存到数据库
            appDb.bookDao.update(updated)
            // 记录 AI 匹配结果
            val meta = AiBookMetadata(
                bookUrl = book.bookUrl,
                kind = candidate.kind,
                tags = candidate.kind,
                summary = candidate.intro,
                authorIntro = candidate.authorIntro
            )
            appDb.aiBookMetadataDao.insert(meta)
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