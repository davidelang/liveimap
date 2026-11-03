package org.dlang.liveimap.ui.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.SelectedAddress
import org.dlang.liveimap.settings.AddressBookEntry
import org.dlang.liveimap.settings.androidSourceId
import org.dlang.liveimap.settings.pineSourceId
import java.util.concurrent.atomic.AtomicReference

data class CompletionEntry(
    val nickname: String,
    val displayName: String,
    val email: String,
)

data class CompletionSource(
    val label: String,
    val entries: List<CompletionEntry>,
)

data class AddressSuggestion(
    val sourceLabel: String,
    val nickname: String,
    val displayName: String,
    val email: String,
    val distribution: Boolean,
    val members: List<SelectedAddress>,
)

/** Tests count one nickname index per source per [completeAddress] call, not one per entry. */
internal var completionNicknameIndexes: Int = 0

internal fun resetCompletionNicknameIndexes() {
    completionNicknameIndexes = 0
}

fun completeAddress(typed: String, sources: List<CompletionSource>): List<AddressSuggestion> {
    val needle = typed.trim()
    if (needle.isEmpty()) return emptyList()
    val exact = ArrayList<AddressSuggestion>()
    val contains = ArrayList<AddressSuggestion>()
    for (source in sources) {
        var index: Map<String, AlpineEntry>? = null
        fun sourceIndex(): Map<String, AlpineEntry> {
            index?.let { return it }
            val built = nicknameIndex(source.entries)
            index = built
            return built
        }
        for (entry in source.entries) {
            val exactNick = entry.nickname.equals(needle, ignoreCase = true)
            val loose = !exactNick && (
                entry.displayName.contains(needle, ignoreCase = true) ||
                entry.email.contains(needle, ignoreCase = true)
            )
            if (!exactNick && !loose) continue
            val suggestion = if (isDistributionAddress(entry.email)) {
                suggestionForDistribution(source.label, entry, sourceIndex())
            } else {
                plainSuggestion(source.label, entry)
            }
            if (exactNick) exact.add(suggestion) else contains.add(suggestion)
        }
    }
    exact.addAll(contains)
    return exact
}

private fun nicknameIndex(entries: List<CompletionEntry>): Map<String, AlpineEntry> {
    completionNicknameIndexes++
    val index = HashMap<String, AlpineEntry>(entries.size)
    for (entry in entries) {
        val key = nicknameIndexKey(entry.nickname)
        if (key.isEmpty() || key in index) continue
        index[key] = AlpineEntry(entry.nickname, entry.displayName, entry.email.trim(), "", "")
    }
    return index
}

private fun suggestionForDistribution(
    label: String,
    entry: CompletionEntry,
    index: Map<String, AlpineEntry>,
): AddressSuggestion {
    val members = pickedAddresses(
        AlpineEntry(
            nickname = entry.nickname,
            fullname = entry.displayName,
            address = entry.email.trim(),
            fcc = "",
            comments = "",
        ),
        // F189: nicknames in the list resolve against the same source's entries.
        index,
    )
    return AddressSuggestion(
        sourceLabel = label,
        nickname = entry.nickname,
        displayName = entry.displayName,
        email = entry.email,
        distribution = true,
        members = members,
    )
}

private fun plainSuggestion(label: String, entry: CompletionEntry): AddressSuggestion = AddressSuggestion(
    sourceLabel = label,
    nickname = entry.nickname,
    displayName = entry.displayName,
    email = entry.email,
    distribution = false,
    members = emptyList(),
)

private fun isDistributionAddress(address: String): Boolean {
    val trimmed = address.trim()
    return trimmed.length >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")
}

fun suggestionText(suggestion: AddressSuggestion): String {
    val name = suggestion.displayName.ifEmpty { suggestion.nickname }
    val body = when {
        suggestion.distribution -> name.ifEmpty { "Distribution list" }
        name.isEmpty() -> suggestion.email
        suggestion.email.isEmpty() -> name
        else -> "$name ${suggestion.email}"
    }
    return "$body — ${suggestion.sourceLabel}"
}

fun withSourceEnabled(sources: List<String>, id: String, enabled: Boolean): List<String> {
    if (!enabled) return sources.filterNot { it == id }
    if (sources.contains(id)) return sources
    return sources + id
}

fun moveCompletionSource(sources: List<String>, id: String, up: Boolean): List<String> {
    val index = sources.indexOf(id)
    if (index < 0) return sources
    val target = if (up) index - 1 else index + 1
    if (target < 0 || target >= sources.size) return sources
    val next = sources.toMutableList()
    val moved = next.removeAt(index)
    next.add(target, moved)
    return next
}

fun completionSourcesInOrder(
    books: List<AddressBookEntry>,
    pineByMailbox: Map<String, List<AlpineEntry>>,
    androidEntries: Map<String, List<CompletionEntry>>,
    androidLabels: Map<String, String>,
): List<CompletionSource> {
    val out = ArrayList<CompletionSource>(books.size)
    for (book in books) {
        if (book.kind == "pine") {
            if (book.mailbox.isEmpty()) continue
            val entries = pineByMailbox[book.mailbox].orEmpty().map {
                CompletionEntry(it.nickname, it.fullname, it.address)
            }
            out.add(CompletionSource(book.mailbox, entries))
        } else if (book.kind == "android") {
            val id = androidSourceId(book.accountType, book.accountName)
            out.add(CompletionSource(androidLabels[id] ?: book.accountName.ifEmpty { id }, androidEntries[id].orEmpty()))
        }
    }
    return out
}

/** F62: one cached book per (account, mailbox); failures and notices are not cached. */
private val pineEntries = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<AlpineEntry>>>()
private val pineLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun pineKey(accountId: String, mailbox: String) = "$accountId\u0000$mailbox"

fun pineBookIsLoaded(accountId: String, mailbox: String): Boolean {
    val deferred = pineEntries[pineKey(accountId, mailbox)] ?: return false
    return deferred.isCompleted && deferred.getCompletionExceptionOrNull() == null
}

/** Drop cached completion data (after an address-book write or Take Address, or a settings change). */
fun forgetCompletionCaches() {
    pineEntries.clear()
    androidContacts.updateAndGet { it.copy(sets = null, entries = null) }
}

suspend fun pineEntriesOnce(session: MailSession, accountId: String, mailbox: String): List<AlpineEntry> {
    if (mailbox.isEmpty()) return emptyList()
    val key = pineKey(accountId, mailbox)
    val deferred = pineEntries.computeIfAbsent(key) { startPineLoad(key, session, mailbox) }
    try {
        return deferred.await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        pineEntries.remove(key, deferred)
        throw error
    }
}

/** One load. A successful book, including an empty one, stays in the map. A failure is removed. */
private fun startPineLoad(key: String, session: MailSession, mailbox: String): Deferred<List<AlpineEntry>> {
    val created = CompletableDeferred<List<AlpineEntry>>()
    pineLoadScope.launch {
        try {
            val loaded = loadAlpineBook(session, mailbox)
            val notice = loaded.notice
            if (notice != null) throw MailFailure(notice)
            created.complete(loaded.entries)
        } catch (error: CancellationException) {
            pineEntries.remove(key, created)
            created.completeExceptionally(error)
        } catch (error: Exception) {
            pineEntries.remove(key, created)
            created.completeExceptionally(error)
        }
    }
    return created
}

data class AndroidContactSet(
    val id: String,
    val label: String,
)

private data class AndroidContactSnapshot(
    val sets: List<AndroidContactSet>?,
    val entries: Map<String, List<CompletionEntry>>?,
    val androidLoadedAt: Long,
)

private val androidContacts = AtomicReference(
    AndroidContactSnapshot(sets = null, entries = null, androidLoadedAt = 0L),
)
private val AndroidCacheMs: Long
    get() = org.dlang.liveimap.settings.SettingsDefaults.androidContactsCacheSeconds * 1000L

fun hasReadContacts(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** A loaded book, including an empty one, or a missing READ_CONTACTS permission. Permission is not cached. */
sealed class AndroidContacts<out T> {
    data class Ready<T>(val value: T) : AndroidContacts<T>()
    data object PermissionNeeded : AndroidContacts<Nothing>()
}

fun androidContactSetsOnce(
    resolver: ContentResolver,
    packageManager: PackageManager,
): AndroidContacts<List<AndroidContactSet>> =
    loadAndroidContactSets { queryAndroidContactSets(resolver, packageManager) }

internal fun loadAndroidContactSets(load: () -> List<AndroidContactSet>): AndroidContacts<List<AndroidContactSet>> {
    androidContacts.get().sets?.let { return AndroidContacts.Ready(it) }
    val loaded = try {
        load()
    } catch (_: SecurityException) {
        return AndroidContacts.PermissionNeeded
    }
    val published = androidContacts.updateAndGet { current ->
        if (current.sets != null) current else current.copy(sets = loaded)
    }
    return AndroidContacts.Ready(published.sets ?: loaded)
}

fun androidEntriesOnce(resolver: ContentResolver): AndroidContacts<Map<String, List<CompletionEntry>>> =
    loadAndroidEntries { queryAndroidEntries(resolver) }

internal fun loadAndroidEntries(
    load: () -> Map<String, List<CompletionEntry>>,
): AndroidContacts<Map<String, List<CompletionEntry>>> {
    val seen = androidContacts.get()
    val now = System.currentTimeMillis()
    // F62: contacts added later show up after a few minutes (or at once after forgetCompletionCaches).
    if (seen.entries != null && now - seen.androidLoadedAt < AndroidCacheMs) {
        return AndroidContacts.Ready(seen.entries)
    }
    val sampled = System.currentTimeMillis()
    val loaded = try {
        load()
    } catch (_: SecurityException) {
        return AndroidContacts.PermissionNeeded
    }
    val afterQuery = System.currentTimeMillis()
    val published = androidContacts.updateAndGet { current ->
        val newerInside = current.entries != null &&
            current.androidLoadedAt >= sampled &&
            afterQuery - current.androidLoadedAt < AndroidCacheMs
        if (newerInside) current else current.copy(entries = loaded, androidLoadedAt = sampled)
    }
    return AndroidContacts.Ready(published.entries ?: loaded)
}

private fun owningAppLabel(packageManager: PackageManager, accountType: String): String? {
    if (accountType.isEmpty()) return null
    return try {
        val info = packageManager.getApplicationInfo(accountType, 0)
        val label = packageManager.getApplicationLabel(info).toString().trim()
        label.ifEmpty { null }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

private fun contactSetLabel(
    packageManager: PackageManager,
    accountType: String,
    accountName: String,
): String {
    val owning = owningAppLabel(packageManager, accountType)
    if (!owning.isNullOrEmpty()) return owning
    return accountName
}

private fun cursorString(cursor: android.database.Cursor, index: Int): String {
    if (index < 0 || cursor.isNull(index)) return ""
    return cursor.getString(index) ?: ""
}

private fun queryAndroidContactSets(
    resolver: ContentResolver,
    packageManager: PackageManager,
): List<AndroidContactSet> {
    val seen = LinkedHashMap<String, AndroidContactSet>()
    val cursor = resolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
        ),
        "${ContactsContract.RawContacts.DELETED}=0",
        null,
        null,
    ) ?: return emptyList()
    cursor.use {
        val typeIndex = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
        val nameIndex = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
        while (it.moveToNext()) {
            val type = cursorString(it, typeIndex)
            val name = cursorString(it, nameIndex)
            val id = androidSourceId(type, name)
            if (seen.containsKey(id)) continue
            seen[id] = AndroidContactSet(id, contactSetLabel(packageManager, type, name))
        }
    }
    return seen.values.toList()
}

private data class RawContact(val sourceId: String, val displayName: String)

private fun queryAndroidEntries(resolver: ContentResolver): Map<String, List<CompletionEntry>> {
    val raws = HashMap<Long, RawContact>()
    resolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
        ),
        "${ContactsContract.RawContacts.DELETED}=0",
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
        val typeIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
        val nameIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
        val displayIndex = cursor.getColumnIndex(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val id = cursor.getLong(idIndex)
            raws[id] = RawContact(
                androidSourceId(cursorString(cursor, typeIndex), cursorString(cursor, nameIndex)),
                cursorString(cursor, displayIndex),
            )
        }
    }
    val nicknames = HashMap<Long, String>()
    resolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Nickname.NAME,
        ),
        "${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Nickname.NAME)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val id = cursor.getLong(idIndex)
            if (nicknames.containsKey(id)) continue
            val nickname = cursorString(cursor, nameIndex)
            if (nickname.isEmpty()) continue
            nicknames[id] = nickname
        }
    }
    val out = LinkedHashMap<String, MutableList<CompletionEntry>>()
    resolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID)
        val emailIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val displayIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val rawId = cursor.getLong(idIndex)
            val raw = raws[rawId] ?: continue
            val email = cursorString(cursor, emailIndex)
            if (email.isEmpty()) continue
            val display = raw.displayName.ifEmpty { cursorString(cursor, displayIndex) }
            val nickname = nicknames[rawId] ?: ""
            out.getOrPut(raw.sourceId) { ArrayList() }
                .add(CompletionEntry(nickname, display, email))
        }
    }
    return out
}
