package com.example.webview

import android.net.Uri
import com.example.domain.model.WinGoGameConfig

/**
 * Multi-signal website authentication state machine for 9K Club WebView monitoring.
 * Strictly decoupled from upstream GetPanel ApiAuthenticationState.
 */
enum class WebsiteAuthenticationState(val uiLabel: String) {
    WEBVIEW_LOADING("WEBVIEW_LOADING"),
    LOGIN_PAGE("LOGIN_PAGE"),
    LOGGED_OUT("LOGGED_OUT"),
    LOGIN_IN_PROGRESS("LOGIN_IN_PROGRESS"),
    LOGIN_CONFIRMED("LOGIN_CONFIRMED"),
    SESSION_EXPIRED("SESSION_EXPIRED"),
    LOGOUT_IN_PROGRESS("LOGOUT_IN_PROGRESS"),
    UNKNOWN("UNKNOWN")
}

enum class UrlRouteCategory(val label: String) {
    LOADING("LOADING"),
    LOGIN_ROUTE("LOGIN_ROUTE"),
    REGISTER_ROUTE("REGISTER_ROUTE"),
    LOGOUT_ROUTE("LOGOUT_ROUTE"),
    ACCOUNT_ROUTE("ACCOUNT_ROUTE"),
    GAME_ROUTE("GAME_ROUTE"),
    HOME_ROUTE("HOME_ROUTE"),
    UNTRUSTED_OR_UNKNOWN("UNTRUSTED_OR_UNKNOWN")
}

enum class EvidenceConfidenceLevel(val label: String) {
    NONE("NONE"),
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH"),
    CONFLICTING("CONFLICTING")
}

data class AuthDomIndicator(
    val name: String,
    val selectorOrCondition: String,
    val expectedState: WebsiteAuthenticationState,
    val reliabilityWeight: Double,
    val isRequired: Boolean,
    val isVerifiedOnLiveSite: Boolean = false,
    val notes: String = ""
)

/**
 * Configurable registry of non-sensitive DOM and route indicators used by [WebsiteAuthenticationMonitor].
 *
 * Honesty & privacy rules:
 * - Default indicators use ONLY standard W3C/ARIA HTML semantics, already-observed modal classes
 *   (`.van-dialog`, `.van-toast`, `.el-message-box`), and observed 9K Club URL route patterns.
 * - Never fabricates unverified site-specific CSS selectors.
 * - Live-discovered selectors can be registered explicitly after real DOM verification via
 *   [registerDiscoveredIndicator].
 * - Explicitly forbids sensitive field names (tokens, passwords, OTPs, cookies, phones, balances).
 */
class WebsiteAuthIndicatorRegistry(
    initialIndicators: List<AuthDomIndicator> = DEFAULT_INDICATORS
) {
    private val lock = Any()
    private val indicators = LinkedHashMap<String, AuthDomIndicator>()

    init {
        initialIndicators.forEach { indicator ->
            indicators[indicator.name] = indicator
        }
    }

    fun getIndicators(): List<AuthDomIndicator> = synchronized(lock) {
        indicators.values.toList()
    }

    fun getVerifiedIndicators(): List<AuthDomIndicator> = synchronized(lock) {
        indicators.values.filter { it.isVerifiedOnLiveSite }
    }

    fun getIndicatorsForState(state: WebsiteAuthenticationState): List<AuthDomIndicator> = synchronized(lock) {
        indicators.values.filter { it.expectedState == state }
    }

    fun registerDiscoveredIndicator(indicator: AuthDomIndicator) {
        require(indicator.name.isNotBlank()) { "Indicator name must not be blank." }
        require(indicator.reliabilityWeight in 0.0..1.0) { "Reliability weight must be in 0.0..1.0." }
        require(!containsSensitiveKeyword(indicator.name) && !containsSensitiveKeyword(indicator.selectorOrCondition)) {
            "Indicator must never target passwords, tokens, OTPs, cookies, phones, or balances."
        }
        synchronized(lock) {
            indicators[indicator.name] = indicator
        }
    }

    fun unregisterIndicator(name: String): Boolean = synchronized(lock) {
        indicators.remove(name) != null
    }

    fun exportSafeCustomSelectorsForState(state: WebsiteAuthenticationState): List<String> = synchronized(lock) {
        indicators.values
            .filter {
                it.expectedState == state &&
                    it.isVerifiedOnLiveSite &&
                    it.selectorOrCondition.startsWith("css:")
            }
            .map { it.selectorOrCondition.removePrefix("css:").trim() }
            .filter { it.isNotEmpty() && !containsSensitiveKeyword(it) }
    }

    companion object {
        val DEFAULT_INDICATORS: List<AuthDomIndicator> = listOf(
            AuthDomIndicator(
                name = "visible_password_input",
                selectorOrCondition = "css:input[type=\"password\"]",
                expectedState = WebsiteAuthenticationState.LOGIN_PAGE,
                reliabilityWeight = 0.95,
                isRequired = false,
                isVerifiedOnLiveSite = true,
                notes = "Standard HTML password field visible in DOM indicates active login or registration form."
            ),
            AuthDomIndicator(
                name = "visible_guest_auth_action",
                selectorOrCondition = "semantic:button|a|[role=button] text matches /^(log in|sign in|register|create account)$/i",
                expectedState = WebsiteAuthenticationState.LOGGED_OUT,
                reliabilityWeight = 0.75,
                isRequired = false,
                isVerifiedOnLiveSite = true,
                notes = "Visible unauthenticated Login/Register action without password form."
            ),
            AuthDomIndicator(
                name = "visible_logout_control",
                selectorOrCondition = "semantic:button|a|[role=button]|[data-action=logout] text matches /^(log out|sign out)$/i",
                expectedState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                reliabilityWeight = 0.80,
                isRequired = false,
                isVerifiedOnLiveSite = false,
                notes = "Supporting authenticated UI evidence only; rejected if conflicting login form is visible."
            ),
            AuthDomIndicator(
                name = "visible_account_profile_nav",
                selectorOrCondition = "semantic:visible nav/anchor link to account/profile/center route without guest auth CTA",
                expectedState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                reliabilityWeight = 0.45,
                isRequired = false,
                isVerifiedOnLiveSite = false,
                notes = "Weak supporting indicator only; insufficient alone to confirm login without logout control or callback."
            ),
            AuthDomIndicator(
                name = "visible_session_expired_dialog",
                selectorOrCondition = "css:[role=\"dialog\"], [role=\"alert\"], .van-dialog, .van-toast, .el-message-box",
                expectedState = WebsiteAuthenticationState.SESSION_EXPIRED,
                reliabilityWeight = 0.95,
                isRequired = false,
                isVerifiedOnLiveSite = true,
                notes = "Visible session expiration modal/toast prompt."
            ),
            AuthDomIndicator(
                name = "website_session_callback",
                selectorOrCondition = "bridge:WEBSITE_AUTH_STATE / WebsiteSessionBridge",
                expectedState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                reliabilityWeight = 1.0,
                isRequired = false,
                isVerifiedOnLiveSite = false,
                notes = "Origin-validated explicit website authentication callback."
            )
        )

        private val SENSITIVE_KEYWORDS = setOf(
            "token", "bearer", "password", "passwd", "pwd", "otp", "pin",
            "cookie", "cookies", "phone", "mobile", "tel", "balance",
            "walletbalance", "uid", "userid", "accountid", "secret",
            "authorization", "localstorage", "sessionstorage", "document.cookie"
        )

        fun containsSensitiveKeyword(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val lower = value.lowercase()
            // Allow standard W3C input[type="password"] selector check, forbid reading value/field keys
            if (lower == "css:input[type=\"password\"]" || lower == "input[type=\"password\"]") {
                return false
            }
            return SENSITIVE_KEYWORDS.any { keyword ->
                lower == keyword ||
                    lower.contains("\"$keyword\"") ||
                    lower.contains("'$keyword'") ||
                    lower.contains(".$keyword") ||
                    lower.contains("${keyword}_") ||
                    lower.contains("_$keyword")
            }
        }

        private val ALLOWED_DOM_FLAG_KEYS = setOf(
            "hasvisiblepasswordinput"
        )

        fun hasSensitivePayloadKeys(keys: Iterable<String>): Boolean {
            return keys.any { key ->
                val normalized = key.trim().lowercase()
                if (normalized in ALLOWED_DOM_FLAG_KEYS) return@any false
                SENSITIVE_KEYWORDS.contains(normalized) ||
                    normalized.contains("token") ||
                    normalized.contains("password") ||
                    normalized.contains("otp") ||
                    normalized.contains("cookie") ||
                    normalized.contains("phone") ||
                    normalized.contains("mobile") ||
                    normalized.contains("balance") ||
                    normalized == "uid" ||
                    normalized.contains("accountid") ||
                    normalized.contains("secret") ||
                    normalized.contains("authorization")
            }
        }

        fun isExactApprovedOriginUrl(urlString: String?): Boolean {
            if (urlString.isNullOrBlank()) return false
            val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.port != -1 && uri.port != 443) return false
            val host = uri.host?.lowercase() ?: return false
            return WinGoGameConfig.ALLOWED_WEBVIEW_HOSTS.any { allowed ->
                host == allowed.lowercase()
            }
        }

        fun extractApprovedOrigin(urlString: String?): String? {
            if (!isExactApprovedOriginUrl(urlString)) return null
            val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            return "https://$host"
        }

        fun classifyUrlRoute(urlString: String?): UrlRouteCategory {
            if (urlString.isNullOrBlank()) return UrlRouteCategory.LOADING
            if (!isExactApprovedOriginUrl(urlString)) return UrlRouteCategory.UNTRUSTED_OR_UNKNOWN
            val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return UrlRouteCategory.UNTRUSTED_OR_UNKNOWN
            val path = uri.encodedPath?.lowercase().orEmpty()
            val fragment = uri.encodedFragment?.substringBefore('?')?.lowercase().orEmpty()
            val combined = "$path#$fragment"

            return when {
                combined.contains("logout") || combined.contains("signout") -> UrlRouteCategory.LOGOUT_ROUTE
                combined.contains("/register") || combined.contains("signup") -> UrlRouteCategory.REGISTER_ROUTE
                combined.contains("/login") || combined.contains("signin") -> UrlRouteCategory.LOGIN_ROUTE
                combined.contains("wingo") || combined.contains("/lottery") || combined.contains("/game") ->
                    UrlRouteCategory.GAME_ROUTE
                combined.contains("/account") ||
                    combined.contains("/profile") ||
                    combined.contains("/center") ||
                    combined.contains("/main") ||
                    combined.contains("/wallet") ||
                    combined.contains("/vip") -> UrlRouteCategory.ACCOUNT_ROUTE
                path == "/" || path.isEmpty() || combined.contains("/home") -> UrlRouteCategory.HOME_ROUTE
                else -> UrlRouteCategory.HOME_ROUTE
            }
        }

        fun sanitizeUrl(rawUrl: String?): String? {
            if (rawUrl.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val path = uri.encodedPath?.takeIf { it.isNotBlank() } ?: "/"
            val fragmentRoute = uri.encodedFragment?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
            return if (fragmentRoute != null) {
                "$scheme://$host$path#$fragmentRoute"
            } else {
                "$scheme://$host$path"
            }
        }

        fun sanitizeIndicatorName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.trim()
                .take(48)
                .replace(Regex("[^a-zA-Z0-9_+-]"), "")
                .takeIf { it.isNotEmpty() }
        }
    }
}
