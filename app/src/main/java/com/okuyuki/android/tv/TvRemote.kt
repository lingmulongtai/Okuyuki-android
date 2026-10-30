package com.okuyuki.android.tv

import android.content.Context
import androidx.annotation.StringRes
import com.okuyuki.android.R
import com.okuyuki.android.host.HostRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A line for the phone's TV section: a string resource and its arguments, resolved in the app's current language. */
data class TvMessage(@StringRes val text: Int, val args: List<String> = emptyList())

/** The phone's "watch on TV": finds LG TVs and opens Okuyuki TV pointed at this phone. */
class TvRemote(private val context: Context, private val scope: CoroutineScope) {
    private val credentials = TvCredentials(context)
    private val launcher = TvLauncher(context, credentials)
    private val _tvs = MutableStateFlow(credentials.known())
    /** TVs seen before and TVs found now, by name. */
    val tvs = _tvs.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<TvMessage?>(null)
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
                if (found.isEmpty()) _message.value = TvMessage(R.string.tv_none_found)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _message.value = TvMessage(R.string.tv_search_failed) }
            finally { _searching.value = false }
        }
    }

    /** Opens Okuyuki TV on [tv] with this phone's address, so the TV connects without typing anything. */
    fun open(tv: FoundTv) {
        if (_busy.value) return
        val host = HostRuntime.state.value
        val phone = phoneAddress(host.addresses, tv.address)
        if (!host.running || host.port == 0 || phone == null) {
            _message.value = TvMessage(R.string.tv_turn_on_first)
            return
        }
        _busy.value = true
        _message.value = TvMessage(R.string.tv_connecting, listOf(tv.name))
        scope.launch {
            try {
                val params = buildJsonObject { put("host", phone); put("port", host.port); put("deviceId", host.deviceId) }
                launcher.launch(tv, params) { _message.value = TvMessage(R.string.tv_allow_on_tv) }
                credentials.remember(tv)
                _tvs.value = credentials.known()
                _message.value = TvMessage(R.string.tv_opened, listOf(tv.name))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LaunchException) { _message.value = TvMessage(error.reason, listOfNotNull(error.detail)) }
            catch (_: Exception) { _message.value = TvMessage(R.string.tv_failed) }
            finally { _busy.value = false }
        }
    }

    /** Opens Okuyuki TV on a TV that discovery cannot see, by its IP address. */
    fun openAddress(text: String) {
        val address = text.trim()
        if (!IPV4.matches(address) || address.split('.').any { it.toInt() > 255 }) {
            _message.value = TvMessage(R.string.tv_address_format)
            return
        }
        open(_tvs.value.firstOrNull { it.address == address } ?: FoundTv("ip:$address", context.getString(R.string.tv_unnamed, address), address))
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
