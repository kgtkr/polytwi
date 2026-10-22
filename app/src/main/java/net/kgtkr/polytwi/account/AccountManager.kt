package net.kgtkr.polytwi.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kgtkr.polytwi.model.Account
import java.util.UUID

class AccountManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "encrypted_accounts_pref",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to standard prefs if hardware keystore fails
        context.getSharedPreferences("accounts_pref", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    private val _unreadAccountIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadAccountIds: StateFlow<Set<String>> = _unreadAccountIds.asStateFlow()

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        val serialized = prefs.getString("accounts_list", null)
        val list = if (serialized != null) {
            try {
                json.decodeFromString<List<Account>>(serialized)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (list.isEmpty()) {
            val defaultAccount = Account(
                id = UUID.randomUUID().toString(),
                name = "Account 1",
                profileName = "account_${UUID.randomUUID()}"
            )
            _accounts.value = listOf(defaultAccount)
            _activeAccountId.value = defaultAccount.id
            saveAccountsInternal(_accounts.value)
        } else {
            _accounts.value = list
            val savedActive = prefs.getString("active_account_id", null)
            _activeAccountId.value = if (list.any { it.id == savedActive }) savedActive else list.first().id
        }
    }

    fun setActiveAccount(id: String) {
        if (_accounts.value.any { it.id == id }) {
            _activeAccountId.value = id
            prefs.edit().putString("active_account_id", id).apply()
            clearAccountUnread(id)
        }
    }

    fun markAccountUnread(id: String) {
        if (_activeAccountId.value != id) {
            _unreadAccountIds.value = _unreadAccountIds.value + id
        }
    }

    fun clearAccountUnread(id: String) {
        if (id in _unreadAccountIds.value) {
            _unreadAccountIds.value = _unreadAccountIds.value - id
        }
    }

    fun addAccount(name: String): Account {
        val newAccount = Account(
            id = UUID.randomUUID().toString(),
            name = name,
            profileName = "account_${UUID.randomUUID()}"
        )
        val updated = _accounts.value + newAccount
        _accounts.value = updated
        _activeAccountId.value = newAccount.id
        saveAccountsInternal(updated)
        prefs.edit().putString("active_account_id", newAccount.id).apply()
        return newAccount
    }

    fun updateAccount(account: Account) {
        val updated = _accounts.value.map {
            if (it.id == account.id) account else it
        }
        _accounts.value = updated
        saveAccountsInternal(updated)
    }

    fun updateAccountName(id: String, newName: String) {
        val target = _accounts.value.find { it.id == id } ?: return
        updateAccount(target.copy(name = newName))
    }

    fun removeAccount(id: String): Account? {
        val target = _accounts.value.find { it.id == id } ?: return null
        if (_accounts.value.size <= 1) return null // Do not remove last account

        val updated = _accounts.value.filter { it.id != id }
        _accounts.value = updated
        if (_activeAccountId.value == id) {
            _activeAccountId.value = updated.first().id
            prefs.edit().putString("active_account_id", updated.first().id).apply()
        }
        clearAccountUnread(id)
        saveAccountsInternal(updated)
        return target
    }

    fun getAccountByChannelId(channelId: String): Account? {
        return _accounts.value.find { it.channelId == channelId }
    }

    fun getAccountById(id: String): Account? {
        return _accounts.value.find { it.id == id }
    }

    private fun saveAccountsInternal(list: List<Account>) {
        val serialized = json.encodeToString(list)
        prefs.edit().putString("accounts_list", serialized).apply()
    }
}
