package com.minsu.guardapp.data

import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.domain.repository.DocumentRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultDocumentRepository @Inject constructor(
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : DocumentRepository {

    /**
     * Remembered for the session, and deliberately no further.
     *
     * Everything else the app caches, it caches so a guard at a perimeter post with no signal can
     * still use it. That reasoning does not reach here: the URL is only worth having if the PDF
     * behind it can be fetched, and offline it cannot. Writing it to the database would buy a
     * link that opens a browser to a connection error — precision about nothing.
     */
    private val urls = ConcurrentHashMap<String, String>()

    override suspend fun url(identifier: String): String? {
        urls[identifier]?.let { return it }

        val result = errors.call { api.document(identifier).data.url }

        return (result as? ApiResult.Success)?.value
            ?.takeIf { it.isNotBlank() }
            ?.also { urls[identifier] = it }
    }
}
