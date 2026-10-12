package com.okuyuki.android.tv

import android.content.Context
import com.okuyuki.android.host.HostRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The phone's "テレビで見る": finds LG TVs and opens Okuyuki TV pointed at this phone. */
class TvRemote(context: Context, private val scope: CoroutineScope) {
    private val credentials = TvCredentials(context)
    private val launcher = TvLauncher(context, credentials)
    private val _tvs = MutableStateFlow(credentials.known())
    /** TVs seen before and TVs found now, by name. */
    val tvs = _tvs.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun search() {
        if (_searching.value) return
        _searching.value = true
        _message.value = null
        scope.launch {
            try {
                val found = launcher.discover()
                found.forEach(credentials::remember)
                _tvs.value = credentials.known()
                if (found.isEmpty()) _message.value = "テレビが見つかりませんでした。テレビの電源を入れ、スマホと同じ Wi-Fi につないでください。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _message.value = "テレビを探せませんでした。Wi-Fi を確認してください。" }
            finally { _searching.value = false }
        }
    }

    /** Opens Okuyuki TV on [tv] with this phone's address, so the TV connects without typing anything. */
    fun open(tv: FoundTv) {
        if (_busy.value) return
        val host = HostRuntime.state.value
        val phone = phoneAddress(host.addresses, tv.address)
        if (!host.running || host.port == 0 || phone == null) {
            _message.value = "先に「接続を待つ」をオンにしてください。"
            return
        }
        _busy.value = true
        _message.value = "${tv.name} に接続しています…"
        scope.launch {
            try {
                val params = buildJsonObject { put("host", phone); put("port", host.port); put("deviceId", host.deviceId) }
                launcher.launch(tv, params) { _message.value = "テレビの画面で「許可」を選んでください。" }
                credentials.remember(tv)
                _tvs.value = credentials.known()
                _message.value = "${tv.name} で Okuyuki TV を開きました。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LaunchException) { _message.value = error.message }
            catch (_: Exception) { _message.value = "テレビに接続できませんでした。テレビの電源と Wi-Fi を確認してください。" }
            finally { _busy.value = false }
        }
    }

    /** Opens Okuyuki TV on a TV that discovery cannot see, by its IP address. */
    fun openAddress(text: String) {
        val address = text.trim()
        if (!IPV4.matches(address) || address.split('.').any { it.toInt() > 255 }) {
            _message.value = "テレビの IP アドレスを「192.168.1.20」の形で入力してください。"
            return
        }
        open(_tvs.value.firstOrNull { it.address == address } ?: FoundTv("ip:$address", "LG テレビ（$address）", address))
    }

    fun forget(tv: FoundTv) {
        credentials.forget(tv.id)
        _tvs.update { list -> list.filterNot { it.id == tv.id } }
    }

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** The phone's address on the TV's network: the same /24 when there is one, else the first. */
        fun phoneAddress(addresses: List<String>, tvAddress: String): String? {
            val hosts = addresses.map { it.substringBeforeLast(':') }
            val prefix = tvAddress.substringBeforeLast('.')
            return hosts.firstOrNull { it.substringBeforeLast('.') == prefix } ?: hosts.firstOrNull()
        }
    }
}
