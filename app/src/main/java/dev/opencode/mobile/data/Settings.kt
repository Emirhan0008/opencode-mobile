package dev.opencode.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "opencode")

data class Connection(val baseUrl: String, val token: String)

/** Kayıtlı bağlantı profili: sunucu adresi + token (veya parola). */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
)

/** Bağlantı profilleri, tema tercihi ve kapatma planı kalıcı olarak saklanır. */
class Settings(private val context: Context) {

    private val keyUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("token")
    private val keyDarkTheme = booleanPreferencesKey("dark_theme")
    private val keyShutdownPlan = stringPreferencesKey("shutdown_plan")
    private val keyBackgroundService = booleanPreferencesKey("background_service")
    private val keyNotifyPermissions = booleanPreferencesKey("notify_permissions")
    private val keyNotifyForms = booleanPreferencesKey("notify_forms")
    private val keyNotifyWork = booleanPreferencesKey("notify_work")
    private val keyQuotaBadge = booleanPreferencesKey("quota_badge")
    private val keyProfiles = stringPreferencesKey("profiles")
    private val keyActiveProfile = stringPreferencesKey("active_profile")
    private val keyQueue = stringPreferencesKey("queue")
    private val keyTelegramBot = stringPreferencesKey("telegram_bot")
    private val keyTelegramStatusUrl = stringPreferencesKey("telegram_status_url")
    private val keyShutdownSession = stringPreferencesKey("shutdown_session")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Planlanmış kapatma:
     * yeni biçim `"<force|soft>|<oturumID>|<HH:MM veya ->|<armedAtEpochMs>"`,
     * eski biçim `"soft" | "force"` (geriye dönük okunur) | null (kurulu değil).
     */
    val shutdownPlan: Flow<String?> = context.dataStore.data.map { prefs -> prefs[keyShutdownPlan] }

    /** Kayıtlı bağlantı profilleri. `profiles` anahtarı hiç yazılmadıysa eski tek
     *  bağlantı (`base_url`+`token`) da `legacy` kimlikli bir profil olarak görünür. */
    val profiles: Flow<List<Profile>> = context.dataStore.data.map { prefs ->
        if (prefs.contains(keyProfiles)) decodeProfiles(prefs[keyProfiles]) else legacyProfiles(prefs)
    }

    /** Aktif profilin kimliği (yoksa null). Eski tek bağlantı `legacy` olarak görünür. */
    val activeProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyActiveProfile]
            ?: if (prefs.contains(keyProfiles)) null else legacyProfiles(prefs).firstOrNull()?.id
    }

    /**
     * Etkin bağlantı, aktif profilden türetilir.
     *
     * GERİYE DÖNÜK UYUM: `profiles` anahtarı HİÇ yazılmadıysa (containsKey=false) eski
     * `base_url` + `token` değerleri okunur. `profiles` bir kez yazıldıysa (boş liste
     * dahil) artık eski anahtarlara düşülmez — son profil silindiğinde eski bağlantı
     * dirilmesin.
     */
    val connection: Flow<Connection?> = context.dataStore.data.map { prefs ->
        if (prefs.contains(keyProfiles)) {
            val activeId = prefs[keyActiveProfile]
            // Aktif profil açıkça kaldırıldıysa (çıkış) bağlantı yok — listeye takılma.
            if (activeId == null) return@map null
            val list = decodeProfiles(prefs[keyProfiles])
            val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            active?.let { Connection(it.baseUrl, it.token) }
        } else {
            val url = prefs[keyUrl]
            val token = prefs[keyToken]
            if (url.isNullOrBlank() || token.isNullOrBlank()) null else Connection(url, token)
        }
    }

    /** true = koyu tema, false = açık tema. */
    val darkTheme: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[keyDarkTheme] ?: true }

    /** Arka plan bağlantı servisi (ana anahtar). false ise servis hiç çalışmaz, arka plan bildirimi olmaz. */
    val backgroundService: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyBackgroundService] ?: true }

    /** İzin isteği bildirimleri. */
    val notifyPermissions: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyNotifyPermissions] ?: true }

    /** Soru (form) bekliyor bildirimleri. */
    val notifyForms: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyNotifyForms] ?: true }

    /** Görev tamamlandı/başarısız bildirimleri. */
    val notifyWork: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyNotifyWork] ?: true }

    /** Evren kota şeridi gösterilsin mi. */
    val quotaBadge: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyQuotaBadge] ?: true }

    /** Çevrimdışı kuyruk (JSON listesi) — biçimini AppViewModel belirler. */
    val queue: Flow<String?> = context.dataStore.data.map { prefs -> prefs[keyQueue] }

    /** Telegram botunun kullanıcı adı (`t.me/<ad>` yedek erişim linki için). */
    val telegramBot: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[keyTelegramBot] ?: DEFAULT_TELEGRAM_BOT }

    /** Telegram köprüsünün yerel durum ucu (örn. `http://192.168.1.93:49375/health`). */
    val telegramStatusUrl: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[keyTelegramStatusUrl].orEmpty() }

    /**
     * Kapatma gözcüsü için ayrılmış oturum kimliği. "otomatik" hedef seçildiğinde
     * rastgele bir oturum yerine BU oturum kullanılır; böylece görev hep aynı yerde
     * durur ve o oturumun çekirdek talimatı kalıcı olur.
     */
    val shutdownSession: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[keyShutdownSession].orEmpty() }

    /** Eski kullanım: tek bağlantıyı kaydeder (profillerin dışında). */
    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[keyUrl] = baseUrl.trimEnd('/')
            prefs[keyToken] = token
        }
    }

    /** Profili ekler/günceller ve aktif yapar. Eski tek bağlantı ilk yazımda listeye taşınır. */
    suspend fun saveProfile(p: Profile) {
        context.dataStore.edit { prefs ->
            val list = migrateLegacy(prefs).toMutableList()
            val idx = list.indexOfFirst { it.id == p.id }
            if (idx >= 0) list[idx] = p else list.add(p)
            prefs[keyProfiles] = json.encodeToString(ListSerializer(Profile.serializer()), list)
            prefs[keyActiveProfile] = p.id
        }
    }

    /**
     * Profili siler. Silinen aktif profilse aktif, kalan ilk profile taşınır;
     * hiç kalmadıysa aktif null yapılır.
     */
    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[keyProfiles]).toMutableList()
            list.removeAll { it.id == id }
            prefs[keyProfiles] = json.encodeToString(ListSerializer(Profile.serializer()), list)
            if (prefs[keyActiveProfile] == id) {
                if (list.isEmpty()) prefs.remove(keyActiveProfile)
                else prefs[keyActiveProfile] = list.first().id
            }
        }
    }

    /** Aktif profili seçer. */
    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit { prefs -> prefs[keyActiveProfile] = id }
    }

    /**
     * Etkin bağlantıyı kaldırır: aktif profil seçimi temizlenir, uygulama bağlanmamış
     * duruma geçer ama kayıtlı profiller LISTEDE KALIR (yeniden bağlanmak için
     * dokunmak yeterlidir). Eski tek bağlantı önce listeye taşınır ki kaybolmasın.
     * Tema ve kapatma planı KORUNUR.
     */
    suspend fun clearConnection() {
        context.dataStore.edit { prefs ->
            if (!prefs.contains(keyProfiles)) {
                // Eski tek bağlantıyı gerçek profile dönüştür; anahtarları temizle.
                prefs[keyProfiles] = json.encodeToString(
                    ListSerializer(Profile.serializer()),
                    legacyProfiles(prefs),
                )
                prefs.remove(keyUrl)
                prefs.remove(keyToken)
            }
            prefs.remove(keyActiveProfile)
        }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyDarkTheme] = dark }
    }

    suspend fun setBackgroundService(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyBackgroundService] = enabled }
    }

    suspend fun setNotifyPermissions(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyNotifyPermissions] = enabled }
    }

    suspend fun setNotifyForms(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyNotifyForms] = enabled }
    }

    suspend fun setNotifyWork(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyNotifyWork] = enabled }
    }

    suspend fun setQuotaBadge(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyQuotaBadge] = enabled }
    }

    suspend fun setShutdownPlan(plan: String?) {
        context.dataStore.edit { prefs ->
            if (plan == null) prefs.remove(keyShutdownPlan) else prefs[keyShutdownPlan] = plan
        }
    }

    /** Çevrimdışı kuyruğu kaydeder (null = boş/temizle). */
    suspend fun setQueue(raw: String?) {
        context.dataStore.edit { prefs ->
            if (raw == null) prefs.remove(keyQueue) else prefs[keyQueue] = raw
        }
    }

    /** Telegram bot kullanıcı adını kaydeder (başındaki `@` temizlenir). */
    suspend fun setTelegramBot(value: String) {
        context.dataStore.edit { prefs ->
            prefs[keyTelegramBot] = value.trim().removePrefix("@")
        }
    }

    /** Telegram durum ucu adresini kaydeder. */
    suspend fun setTelegramStatusUrl(value: String) {
        context.dataStore.edit { prefs -> prefs[keyTelegramStatusUrl] = value.trim() }
    }

    /** Kapatma gözcüsü oturumunu kaydeder (null/boş = kayıt yok). */
    suspend fun setShutdownSession(value: String?) {
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(keyShutdownSession)
            else prefs[keyShutdownSession] = value
        }
    }

    private fun decodeProfiles(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Profile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** Eski tek-bağlantı anahtarlarından görünür profil (`legacy` kimliği). */
    private fun legacyProfiles(prefs: Preferences): List<Profile> {
        val url = prefs[keyUrl]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val token = prefs[keyToken]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val name = url.substringAfter("://").substringBefore("/").ifBlank { url }
        return listOf(Profile(id = LEGACY_ID, name = name, baseUrl = url, token = token))
    }

    /**
     * İlk profil yazımında eski tek bağlantıyı listeye taşır ki kaybolmasın.
     * `profiles` anahtarı bir kez yazıldıktan sonra eski anahtarlara dönülmez.
     */
    private fun migrateLegacy(prefs: MutablePreferences): List<Profile> {
        val existing = decodeProfiles(prefs[keyProfiles])
        if (prefs.contains(keyProfiles)) return existing
        return existing + legacyProfiles(prefs)
    }

    companion object {
        /** Eski tek-bağlantı (base_url+token) görünümünün profil kimliği. */
        const val LEGACY_ID = "legacy"

        /** Kurulu Telegram köprüsünün botu — kullanıcı değiştirene kadar varsayılan. */
        const val DEFAULT_TELEGRAM_BOT = "Opencode_0008_Bot"
    }
}
