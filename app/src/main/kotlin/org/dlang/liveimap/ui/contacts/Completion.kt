package org.dlang.liveimap.ui.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.SelectedAddress
import org.dlang.liveimap.settings.androidSourceId
import org.dlang.liveimap.settings.pineSourceId

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

fun completeAddress(typed: String, sources: List<CompletionSource>): List<AddressSuggestion> {
    val needle = typed.trim()
    if (needle.isEmpty()) return emptyList()
    val exact = ArrayList<AddressSuggestion>()
    val contains = ArrayList<AddressSuggestion>()
    for (source in sources) {
        for (entry in source.entries) {
            val suggestion = suggestionFor(source.label, entry)
            if (entry.nickname.equals(needle, ignoreCase = true)) {
                exact.add(suggestion)
                continue
            }
            if (
                entry.displayName.contains(needle, ignoreCase = true) ||
                entry.email.contains(needle, ignoreCase = true)
            ) {
                contains.add(suggestion)
            }
        }
    }
    exact.addAll(contains)
    return exact
}

private fun suggestionFor(label: String, entry: CompletionEntry): AddressSuggestion {
    val distribution = isDistributionAddress(entry.email)
    val members = if (distribution) {
        pickedAddresses(
            AlpineEntry(
                nickname = entry.nickname,
                fullname = entry.displayName,
                address = entry.email.trim(),
                fcc = "",
                comments = "",
            ),
        )
    } else {
        emptyList()
    }
    return AddressSuggestion(
        sourceLabel = label,
        nickname = entry.nickname,
        displayName = entry.displayName,
        email = entry.email,
        distribution = distribution,
        members = members,
    )
}

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
    ids: List<String>,
    pineEntries: List<AlpineEntry>,
    androidEntries: Map<String, List<CompletionEntry>>,
    androidLabels: Map<String, String>,
): List<CompletionSource> {
    val pine = pineEntries.map { CompletionEntry(it.nickname, it.fullname, it.address) }
    val out = ArrayList<CompletionSource>(ids.size)
    for (id in ids) {
        if (id == pineSourceId) {
            out.add(CompletionSource("Pine", pine))
        } else if (id.startsWith("android|")) {
            out.add(CompletionSource(androidLabels[id] ?: id, androidEntries[id].orEmpty()))
        }
    }
    return out
}

@Volatile
private var pineEntries: List<AlpineEntry>? = null
private val pineMutex = Mutex()

fun pineBookIsLoaded(): Boolean = pineEntries != null

suspend fun pineEntriesOnce(session: MailSession, mailbox: String): List<AlpineEntry> {
    pineEntries?.let { return it }
    if (mailbox.isEmpty()) return emptyList()
    return pineMutex.withLock {
        pineEntries?.let { return@withLock it }
        if (mailbox.isEmpty()) return@withLock emptyList()
        val entries = try {
            val loaded = loadAlpineBook(session, mailbox)
            if (loaded.notice != null) emptyList() else loaded.entries
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            emptyList()
        }
        pineEntries = entries
        entries
    }
}

data class AndroidContactSet(
    val id: String,
    val label: String,
)

private val contactMemory = Any()
private var androidSets: List<AndroidContactSet>? = null
private var androidEntries: Map<String, List<CompletionEntry>>? = null

fun hasReadContacts(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

fun androidContactSetsOnce(resolver: ContentResolver, packageManager: PackageManager): List<AndroidContactSet> {
    synchronized(contactMemory) {
        androidSets?.let { return it }
        val loaded = try {
            queryAndroidContactSets(resolver, packageManager)
        } catch (_: SecurityException) {
            return emptyList()
        }
        androidSets = loaded
        return loaded
    }
}

fun androidEntriesOnce(resolver: ContentResolver): Map<String, List<CompletionEntry>> {
    synchronized(contactMemory) {
        androidEntries?.let { return it }
        val loaded = try {
            queryAndroidEntries(resolver)
        } catch (_: SecurityException) {
            return emptyMap()
        }
        androidEntries = loaded
        return loaded
    }
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
