package com.khixang.panel.android

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Non-exportable Android Keystore key; only AES-GCM ciphertext reaches preferences. */
class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("MonitorPanel.APIKey", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("MonitorPanel.APIKey", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(id: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray()) }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(id, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    fun load(id: String): String {
        val data = prefs.getString(id, null) ?: return ""
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12))); updateAAD(id.toByteArray()) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun delete(id: String) { check(prefs.edit().remove(id).commit()) }
}

data class PanelState(val panels: List<Panel> = emptyList(), val selected: String = "", val nodes: List<J> = emptyList(),
                      val statuses: J = Empty, val loading: Boolean = false, val fresh: Boolean = false,
                      val error: String? = null, val preferences: DashboardPreferences = DashboardPreferences(),
                      val appearance: String = "system", val accent: String = "#007AFF", val connectionRevision: Long = 0) {
    val panel get() = panels.find { it.id == selected }
}

/** Latest connection wins even if a cancelled transport completes late. */
class ConnectionGate(private val factory: (Panel) -> PanelApi) {
    private var generation = 0L
    private var request = 0L
    private var connection: PanelApi? = null
    fun invalidate() { generation++; request++; connection = null }
    suspend fun load(panel: Panel, onResult: (Snapshot) -> Unit) {
        val token = ++generation; ++request
        val candidate = factory(panel)
        val result = try { candidate.snapshot() } catch(e: Exception) { if(token != generation) return else throw e }
        currentCoroutineContext().ensureActive()
        if (token == generation) { connection = candidate; onResult(result) }
    }
    suspend fun refresh(panel: Panel, nodes: List<J>, onResult: (Snapshot) -> Unit) {
        val api = connection ?: return load(panel, onResult)
        val token = generation; val requestID = ++request
        val result = try { api.refresh(nodes) } catch(e: Exception) { if(token != generation || requestID != request) return else throw e }
        currentCoroutineContext().ensureActive()
        if (token == generation && requestID == request) onResult(result)
    }
}

class PanelStore(app: Application): AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("panels", Context.MODE_PRIVATE)
    private val credentials = Credentials(app)
    private val initialPanels = runCatching { parse(prefs.getString("panels", "[]")!!).arr.map(Panel::decode) }.getOrDefault(emptyList())
    private val initialSelected = prefs.getString("selected", "").orEmpty().takeIf { id -> initialPanels.any { it.id == id } } ?: initialPanels.firstOrNull()?.id.orEmpty()
    private val mutable = MutableStateFlow(PanelState(panels = initialPanels, selected = initialSelected,
        preferences = preferencesFor(initialSelected), appearance = prefs.getString("appearance", "system")!!, accent = prefs.getString("accent", "#007AFF")!!))
    val state: StateFlow<PanelState> = mutable.asStateFlow()
    private val gate = ConnectionGate { api(it) }
    private var task: Job? = null
    fun api(panel: Panel = state.value.panel ?: throw ApiError("尚未连接")) = Api(panel, if(panel.backend == Backend.DSTATUS) "" else credentials.load(panel.id))
    private fun preferencesFor(id: String): DashboardPreferences {
        val j = runCatching { parse(prefs.getString("dashboard:$id", "{}")!!) }.getOrDefault(Empty)
        return DashboardPreferences(j["order"].arr.map { it.str }, j["hidden"].arr.map { it.str }.toSet())
    }
    fun select(id: String) {
        if (state.value.selected == id) return
        task?.cancel(); gate.invalidate()
        mutable.update { it.copy(selected = id, nodes = emptyList(), statuses = Empty, fresh = false, loading = false, error = null, preferences = preferencesFor(id)) }
        prefs.edit().putString("selected", id).apply()
        reload()
    }
    fun reload() {
        task?.cancel()
        task = viewModelScope.launch { connect() }
    }
    private suspend fun connect() {
        val panel = state.value.panel ?: return
        mutable.update { it.copy(loading = true, error = null) }
        try { gate.load(panel) { snapshot -> mutable.update { it.copy(nodes = snapshot.nodes, statuses = snapshot.statuses, fresh = true, error = null) } } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { if(state.value.panel == panel) mutable.update { it.copy(error = safeError(e), fresh = false) } }
        finally { if(currentCoroutineContext().isActive && state.value.panel == panel) mutable.update { it.copy(loading = false) } }
    }
    suspend fun poll() {
        val panel = state.value.panel ?: return
        if (state.value.loading) return
        try { gate.refresh(panel, state.value.nodes) { s -> mutable.update { it.copy(nodes = s.nodes, statuses = s.statuses, fresh = true, error = null) } } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { if(state.value.panel == panel) mutable.update { it.copy(error = safeError(e), fresh = false) } }
    }
    suspend fun savePanel(panel: Panel, inputKey: String) {
        val effective = if(panel.backend == Backend.DSTATUS) "" else inputKey.ifEmpty { credentials.load(panel.id) }
        Api(panel, effective).snapshot()
        currentCoroutineContext().ensureActive()
        if(panel.backend == Backend.DSTATUS) credentials.delete(panel.id) else credentials.save(panel.id, effective)
        val updated = state.value.panels.toMutableList().apply {
            val index = indexOfFirst { it.id == panel.id }; if(index < 0) add(panel) else set(index,panel)
        }
        check(prefs.edit().putString("panels", JsonArray(updated.map { it.encoded() }).toString()).putString("selected", panel.id).commit())
        task?.cancel(); gate.invalidate()
        mutable.update { it.copy(panels = updated, selected = panel.id, nodes = emptyList(), statuses = Empty, fresh = false, loading = false, error = null, preferences = preferencesFor(panel.id), connectionRevision = it.connectionRevision + 1) }
        reload()
    }
    fun remove(panel: Panel) {
        credentials.delete(panel.id)
        val list = state.value.panels.filterNot { it.id == panel.id }
        prefs.edit().putString("panels", JsonArray(list.map { it.encoded() }).toString()).remove("dashboard:${panel.id}").apply()
        mutable.update { it.copy(panels = list) }
        if (state.value.selected == panel.id) select(list.firstOrNull()?.id.orEmpty())
    }
    fun dashboard(value: DashboardPreferences) {
        prefs.edit().putString("dashboard:${state.value.selected}", json("order" to value.order, "hidden" to value.hidden.sorted()).toString()).apply()
        mutable.update { it.copy(preferences = value) }
    }
    fun appearance(mode: String) { prefs.edit().putString("appearance", mode).apply(); mutable.update { it.copy(appearance = mode) } }
    fun accent(hex: String) { require(hex.matches(Regex("#[0-9A-Fa-f]{6}"))); prefs.edit().putString("accent", hex).apply(); mutable.update { it.copy(accent = hex) } }
}
