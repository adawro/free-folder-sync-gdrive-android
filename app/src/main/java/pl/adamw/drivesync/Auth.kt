package pl.adamw.drivesync

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

class NeedsSignInException : Exception("Wymagane ponowne połączenie z Google Drive")

/**
 * Token dostępu do Drive. Domyślnie własny klient OAuth użytkownika (CustomOAuth); w buildzie autora
 * opcjonalnie Google Identity (AuthorizationClient) - bez client secret w aplikacji:
 * Google rozpoznaje aplikację po nazwie pakietu i SHA-1 klucza (klient OAuth typu Android).
 * Zakres drive.file: aplikacja widzi tylko pliki i foldery, które sama utworzyła.
 */
object Auth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Wynik może wymagać zgody użytkownika (hasResolution) - wtedy aktywność pokazuje okno Google. */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).await()

    /** Token do wywołań w tle; rzuca NeedsSignInException, gdy potrzebna jest zgoda w aplikacji. */
    suspend fun token(context: Context): String {
        if (!Settings(context).useBuiltinAuth) return CustomOAuth.accessToken(context)
        val result = authorize(context)
        if (result.hasResolution()) throw NeedsSignInException()
        return result.accessToken ?: throw NeedsSignInException()
    }

    /** Usuwa z pamięci podręcznej nieważny token (po HTTP 401), następne token() pobierze nowy. */
    fun invalidate(context: Context, token: String) {
        CustomOAuth.invalidate()
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}
