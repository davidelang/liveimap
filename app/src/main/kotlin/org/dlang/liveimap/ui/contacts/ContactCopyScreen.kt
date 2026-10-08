package org.dlang.liveimap.ui.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.dlang.liveimap.R
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.androidAccountOf
import org.dlang.liveimap.settings.pineSourceId

private enum class CopyStep { Source, Contacts, Destination, Preview }

private data class CopySource(val id: String, val label: String)

private data class AndroidWrite(
    val accountType: String,
    val accountName: String,
    val entries: List<CopyContact>,
)

@Composable
fun ContactCopyScreen() {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    var accountId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        accountId = store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    ContactCopyLoaded(accountId = id, store = store)
}

@Composable
private fun ContactCopyLoaded(accountId: String, store: DataStoreSettingsStore) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val session = remember(accountId) { mailSession(accountId) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(AccountSettings()) }
    var sources by remember { mutableStateOf<List<CopySource>>(emptyList()) }
    var step by remember { mutableStateOf(CopyStep.Source) }
    var sourceId by remember { mutableStateOf("") }
    var destinationId by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<CopyContact>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var destinationContacts by remember { mutableStateOf<List<CopyContact>>(emptyList()) }
    var pineState by remember { mutableStateOf<PineBookState?>(null) }
    var pineMailbox by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(CopyOptions()) }
    var preview by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf<List<CopyContact>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var pendingAndroid by remember { mutableStateOf<AndroidWrite?>(null) }

    fun selectedContacts(): List<CopyContact> = selected.sorted().mapNotNull { contacts.getOrNull(it) }

    fun applyPreview(nextOptions: CopyOptions = options) {
        val chosen = selectedContacts()
        val intoPine = destinationId == pineSourceId
        val outcome = copyContacts(chosen, destinationContacts, intoPine, nextOptions)
        options = nextOptions
        preview = outcome.preview
        pending = outcome.entries
    }

    suspend fun writePineNow() {
        val state = pineState
        if (state == null) {
            notice = context.getString(R.string.copy_not_alpine)
            return
        }
        val settings = store.load()
        val chosen = selectedContacts()
        val result = writePineBook(
            session,
            pineMailbox,
            state.lastUid,
            pending.map { it.asAlpineEntry() },
            settings.addressBookHistory,
            settings.addressBookNeverTrim,
        )
        when (result) {
            is PineWriteResult.Stale -> {
                val again = copyContacts(
                    chosen,
                    result.state.entries.map { it.asCopyContact() },
                    intoPine = true,
                    options = options,
                )
                destinationContacts = result.state.entries.map { it.asCopyContact() }
                pineState = result.state
                preview = again.preview
                pending = again.entries
                notice = context.getString(R.string.copy_changed)
            }
            is PineWriteResult.Wrote -> {
                notice = context.getString(R.string.copy_done)
                finished = true
            }
            is PineWriteResult.NotBook -> notice = result.notice
        }
    }

    fun writeAndroidNow(write: AndroidWrite) {
        writeAndroidContacts(
            appContext.contentResolver,
            write.accountType,
            write.accountName,
            write.entries,
        )
        notice = context.getString(R.string.copy_done)
        finished = true
    }

    val requestWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val write = pendingAndroid
        if (!granted || write == null) {
            notice = context.getString(R.string.settings_contacts_denied)
            pendingAndroid = null
            writing = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                writeAndroidNow(write)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: context.getString(R.string.copy_write_failed)
            } finally {
                pendingAndroid = null
                writing = false
            }
        }
    }

    LaunchedEffect(session) {
        when (val opened = connectAccount(store, session, context.getString(R.string.reader_not_connected))) {
            is ConnectedAccount.Failed -> notice = opened.notice
            is ConnectedAccount.Ready -> {
                account = opened.settings
                val sets = if (!hasReadContacts(context)) {
                    emptyList()
                } else {
                    try {
                        androidContactSetsOnce(appContext.contentResolver, appContext.packageManager)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                sources = enabledCopySources(opened.settings, sets, context)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        val shown = notice
        if (shown != null) Text(shown)
        if (reading) Text(stringResource(R.string.copy_reading))
        when (step) {
            CopyStep.Source -> {
                if (sources.isEmpty() && !reading && notice == null) Text(stringResource(R.string.copy_none_enabled))
                for (source in sources) {
                    TextButton(onClick = {
                        if (reading || writing) return@TextButton
                        scope.launch {
                            reading = true
                            notice = null
                            try {
                                val loaded = loadCopyContacts(source.id, account, session, appContext.contentResolver, context)
                                contacts = loaded.contacts
                                sourceId = source.id
                                selected = emptySet()
                                finished = false
                                step = CopyStep.Contacts
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                notice = error.message ?: context.getString(R.string.copy_read_failed)
                            } finally {
                                reading = false
                            }
                        }
                    }, enabled = !reading && !writing) { Text(source.label) }
                }
            }
            CopyStep.Contacts -> {
                TextButton(onClick = { selected = contacts.indices.toSet() }, enabled = !writing) {
                    Text(stringResource(R.string.copy_select_all))
                }
                contacts.forEachIndexed { index, contact ->
                    TickRow(contactLabel(contact, context), index in selected, enabled = !writing) { on ->
                        selected = if (on) selected + index else selected - index
                    }
                }
                TextButton(onClick = {
                    step = CopyStep.Source
                    notice = null
                }, enabled = !writing) { Text(stringResource(R.string.reader_back)) }
                TextButton(
                    onClick = { step = CopyStep.Destination },
                    enabled = selected.isNotEmpty() && !writing,
                ) { Text(stringResource(R.string.copy_to)) }
            }
            CopyStep.Destination -> {
                val choices = sources.filter { it.id != sourceId }
                if (choices.isEmpty()) Text(stringResource(R.string.copy_no_destination))
                for (choice in choices) {
                    TextButton(onClick = {
                        if (reading || writing) return@TextButton
                        scope.launch {
                            reading = true
                            notice = null
                            try {
                                val loaded = loadCopyContacts(choice.id, account, session, appContext.contentResolver, context)
                                destinationId = choice.id
                                destinationContacts = loaded.contacts
                                pineState = loaded.state
                                pineMailbox = if (choice.id == pineSourceId) account.addressBookMailbox else ""
                                finished = false
                                options = CopyOptions()
                                applyPreview(CopyOptions())
                                step = CopyStep.Preview
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                notice = error.message ?: context.getString(R.string.copy_read_failed)
                            } finally {
                                reading = false
                            }
                        }
                    }, enabled = !reading && !writing) { Text(choice.label) }
                }
                TextButton(onClick = { step = CopyStep.Contacts }, enabled = !writing) { Text(stringResource(R.string.reader_back)) }
            }
            CopyStep.Preview -> {
                val intoPine = destinationId == pineSourceId
                TickRow(stringResource(R.string.copy_merge), options.mergeIntoExisting, enabled = !writing && !finished) { on ->
                    applyPreview(options.copy(mergeIntoExisting = on))
                }
                if (intoPine) {
                    TickRow(stringResource(R.string.copy_one_email), options.oneEntryPerEmail, enabled = !writing && !finished) { on ->
                        applyPreview(options.copy(oneEntryPerEmail = on))
                    }
                    TickRow(
                        stringResource(R.string.copy_append_comments),
                        options.appendDroppedToComments,
                        enabled = !writing && !finished,
                    ) { on ->
                        applyPreview(options.copy(appendDroppedToComments = on))
                    }
                } else {
                    TickRow(stringResource(R.string.copy_no_notes), !options.destinationKeepsNotes, enabled = !writing && !finished) {
                        applyPreview(options.copy(destinationKeepsNotes = !it))
                    }
                    TickRow(stringResource(R.string.copy_no_groups), !options.destinationHasGroups, enabled = !writing && !finished) {
                        applyPreview(options.copy(destinationHasGroups = !it))
                    }
                    TickRow(
                        stringResource(R.string.copy_no_nicknames),
                        !options.destinationKeepsNickname,
                        enabled = !writing && !finished,
                    ) { on ->
                        applyPreview(options.copy(destinationKeepsNickname = !on))
                    }
                }
                if (preview.isEmpty()) Text(stringResource(R.string.copy_unchanged))
                for (line in preview) Text(line)
                TextButton(onClick = { step = CopyStep.Destination }, enabled = !writing) { Text(stringResource(R.string.reader_back)) }
                if (!finished) {
                    TextButton(
                        onClick = {
                            if (writing) return@TextButton
                            writing = true
                            notice = null
                            if (destinationId == pineSourceId) {
                                scope.launch {
                                    try {
                                        writePineNow()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        notice = error.message ?: context.getString(R.string.copy_book_failed)
                                    } finally {
                                        writing = false
                                    }
                                }
                            } else {
                                val accountParts = androidAccountOf(destinationId)
                                if (accountParts == null) {
                                    notice = context.getString(R.string.copy_set_missing)
                                    writing = false
                                    return@TextButton
                                }
                                val write = AndroidWrite(accountParts.first, accountParts.second, pending)
                                val granted = context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    pendingAndroid = write
                                    requestWrite.launch(Manifest.permission.WRITE_CONTACTS)
                                    return@TextButton
                                }
                                scope.launch {
                                    try {
                                        writeAndroidNow(write)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        notice = error.message ?: context.getString(R.string.copy_write_failed)
                                    } finally {
                                        writing = false
                                    }
                                }
                            }
                        },
                        enabled = !writing,
                    ) { Text(stringResource(R.string.settings_confirm)) }
                }
            }
        }
    }
}

@Composable
private fun TickRow(label: String, checked: Boolean, enabled: Boolean, onValue: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onValue(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onValue, enabled = enabled)
        Text(label)
    }
}

private fun contactLabel(contact: CopyContact, context: Context): String {
    if (contact.group || isPineListAddress(contact.address)) {
        return contact.displayName.ifEmpty { contact.nickname }.ifEmpty { context.getString(R.string.copy_list) }
    }
    val name = contact.displayName.ifEmpty { contact.nickname }
    val email = contact.emails.firstOrNull().orEmpty()
    return when {
        name.isEmpty() -> email.ifEmpty { context.getString(R.string.copy_contact) }
        email.isEmpty() -> name
        else -> context.getString(R.string.copy_name_email, name, email)
    }
}

private fun enabledCopySources(settings: AccountSettings, sets: List<AndroidContactSet>, context: Context): List<CopySource> {
    val setById = sets.associateBy { it.id }
    val out = ArrayList<CopySource>()
    for (id in settings.completionSources) {
        if (id == pineSourceId) {
            if (settings.addressBookMailbox.isNotEmpty()) out.add(CopySource(id, context.getString(R.string.settings_pine)))
        } else {
            val set = setById[id] ?: continue
            out.add(CopySource(id, set.label))
        }
    }
    return out
}

private sealed class ConnectedAccount {
    data class Ready(val settings: AccountSettings) : ConnectedAccount()
    data class Failed(val notice: String) : ConnectedAccount()
}

private suspend fun connectAccount(store: DataStoreSettingsStore, session: MailSession, notConnected: String): ConnectedAccount {
    val settings = try {
        store.load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return ConnectedAccount.Failed(error.message ?: notConnected)
    }
    try {
        store.password()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return ConnectedAccount.Failed(error.message ?: notConnected)
    }
    val opened = try {
        session.open(settings)
    } catch (error: CancellationException) {
        throw error
    } catch (error: MailFailure) {
        return ConnectedAccount.Failed(error.text)
    }
    return when (opened) {
        is OpenResult.Rejected -> ConnectedAccount.Failed(opened.capabilities)
        is OpenResult.Failed -> ConnectedAccount.Failed(opened.text)
        OpenResult.Connected -> ConnectedAccount.Ready(settings)
    }
}

private data class LoadedContacts(val contacts: List<CopyContact>, val state: PineBookState?)

private suspend fun loadCopyContacts(
    id: String,
    settings: AccountSettings,
    session: MailSession,
    resolver: ContentResolver,
    context: Context,
): LoadedContacts {
    if (id == pineSourceId) {
        return when (val read = readPineBook(session, settings.addressBookMailbox)) {
            is PineRead.NotBook -> throw IllegalStateException(read.notice)
            is PineRead.Ready -> LoadedContacts(read.state.entries.map { it.asCopyContact() }, read.state)
        }
    }
    val account = androidAccountOf(id) ?: throw IllegalStateException(context.getString(R.string.copy_set_missing))
    return LoadedContacts(loadAndroidContacts(resolver, account.first, account.second, context), null)
}

private class BuiltContact(val rawId: Long) {
    var displayName: String = ""
    var givenName: String = ""
    var familyName: String = ""
    var starred: Boolean = false
    var ringtone: String = ""
    var linked: Boolean = false
    val nicknames = ArrayList<String>()
    val emails = ArrayList<String>()
    var primaryEmail: String = ""
    val phones = ArrayList<CopyField>()
    val postal = ArrayList<CopyField>()
    var organization: String = ""
    var birthday: String = ""
    var photo: String = ""
    val websites = ArrayList<CopyField>()
    val im = ArrayList<CopyField>()
    val custom = ArrayList<CopyField>()
    var note: String = ""

    fun freeze(): CopyContact {
        val ordered = ArrayList<String>()
        if (primaryEmail.isNotEmpty()) ordered.add(primaryEmail)
        for (email in emails) {
            if (ordered.none { it.equals(email, ignoreCase = true) }) ordered.add(email)
        }
        return CopyContact(
            nickname = nicknames.firstOrNull().orEmpty(),
            nicknames = nicknames.toList(),
            displayName = displayName,
            givenName = givenName,
            familyName = familyName,
            emails = ordered,
            note = note,
            phones = phones.toList(),
            postal = postal.toList(),
            organization = organization,
            birthday = birthday,
            photo = photo,
            websites = websites.toList(),
            im = im.toList(),
            custom = custom.toList(),
            starred = starred,
            ringtone = ringtone,
            linked = linked,
            rawId = rawId,
        )
    }
}

private fun loadAndroidContacts(
    resolver: ContentResolver,
    accountType: String,
    accountName: String,
    context: Context,
): List<CopyContact> {
    try {
        return readAndroidContacts(resolver, accountType, accountName, context)
    } catch (_: SecurityException) {
        throw IllegalStateException(context.getString(R.string.copy_unreadable))
    }
}

private fun readAndroidContacts(
    resolver: ContentResolver,
    accountType: String,
    accountName: String,
    context: Context,
): List<CopyContact> {
    val accountClause = accountClause(accountType, accountName)
    val raws = LinkedHashMap<Long, BuiltContact>()
    val contactCounts = HashMap<Long, Int>()
    resolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.RawContacts.STARRED,
            ContactsContract.RawContacts.CUSTOM_RINGTONE,
        ),
        "${ContactsContract.RawContacts.DELETED}=0 AND ${accountClause.selection}",
        accountClause.args,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
        val contactIndex = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
        val displayIndex = cursor.getColumnIndex(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY)
        val starredIndex = cursor.getColumnIndex(ContactsContract.RawContacts.STARRED)
        val ringIndex = cursor.getColumnIndex(ContactsContract.RawContacts.CUSTOM_RINGTONE)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val id = cursor.getLong(idIndex)
            val contactId = if (contactIndex < 0 || cursor.isNull(contactIndex)) 0L else cursor.getLong(contactIndex)
            contactCounts[contactId] = (contactCounts[contactId] ?: 0) + 1
            val built = BuiltContact(id)
            built.displayName = cursorString(cursor, displayIndex)
            built.starred = starredIndex >= 0 && !cursor.isNull(starredIndex) && cursor.getInt(starredIndex) != 0
            built.ringtone = cursorString(cursor, ringIndex)
            raws[id] = built
        }
    }
    val memberships = HashMap<Long, MutableList<Long>>()
    resolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.IS_PRIMARY,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
        val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
        val data1 = cursor.getColumnIndex(ContactsContract.Data.DATA1)
        val data2 = cursor.getColumnIndex(ContactsContract.Data.DATA2)
        val data3 = cursor.getColumnIndex(ContactsContract.Data.DATA3)
        val primaryIndex = cursor.getColumnIndex(ContactsContract.Data.IS_PRIMARY)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val rawId = cursor.getLong(idIndex)
            val built = raws[rawId] ?: continue
            val mime = cursorString(cursor, mimeIndex)
            when (mime) {
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                    val display = cursorString(cursor, data1)
                    if (display.isNotEmpty()) built.displayName = display
                    built.givenName = cursorString(cursor, data2)
                    built.familyName = cursorString(cursor, data3)
                }
                ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                    val nick = cursorString(cursor, data1)
                    if (nick.isNotEmpty()) built.nicknames.add(nick)
                }
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                    val email = cursorString(cursor, data1)
                    if (email.isEmpty()) continue
                    val primary = primaryIndex >= 0 && !cursor.isNull(primaryIndex) && cursor.getInt(primaryIndex) != 0
                    if (primary && built.primaryEmail.isEmpty()) built.primaryEmail = email
                    built.emails.add(email)
                }
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val number = cursorString(cursor, data1)
                    if (number.isNotEmpty()) built.phones.add(CopyField(phoneLabel(cursorInt(cursor, data2), context), number))
                }
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                    val formatted = cursorString(cursor, data1)
                    if (formatted.isNotEmpty()) built.postal.add(CopyField(context.getString(R.string.copy_postal), formatted))
                }
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                    val company = cursorString(cursor, data1)
                    if (company.isNotEmpty() && built.organization.isEmpty()) built.organization = company
                }
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                    if (cursorInt(cursor, data2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                        val date = cursorString(cursor, data1)
                        if (date.isNotEmpty()) built.birthday = date
                    }
                }
                ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                    val url = cursorString(cursor, data1)
                    if (url.isNotEmpty()) built.websites.add(CopyField(context.getString(R.string.copy_website), url))
                }
                ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE -> {
                    val handle = cursorString(cursor, data1)
                    if (handle.isNotEmpty()) built.im.add(CopyField(context.getString(R.string.copy_im), handle))
                }
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                    val note = cursorString(cursor, data1)
                    if (note.isNotEmpty() && built.note.isEmpty()) built.note = note
                }
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> built.photo = "present"
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                    val groupId = cursorLong(cursor, data1)
                    if (groupId > 0L) memberships.getOrPut(groupId) { ArrayList() }.add(rawId)
                }
                else -> {
                    val value = cursorString(cursor, data1)
                    if (mime.isNotEmpty() && value.isNotEmpty() && value.length <= 200) {
                        built.custom.add(CopyField(mime.substringAfterLast('/'), value))
                    }
                }
            }
        }
    }
    val people = raws.values.map { it.freeze() }
    val linkedIds = HashMap<Long, Long>()
    resolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
        "${ContactsContract.RawContacts.DELETED}=0 AND ${accountClause.selection}",
        accountClause.args,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
        val contactIndex = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val contactId = if (contactIndex < 0 || cursor.isNull(contactIndex)) 0L else cursor.getLong(contactIndex)
            linkedIds[cursor.getLong(idIndex)] = contactId
        }
    }
    val withLinks = people.map { contact ->
        val contactId = linkedIds[contact.rawId] ?: 0L
        val linked = contactId != 0L && (contactCounts[contactId] ?: 0) > 1
        if (linked) contact.copy(linked = true) else contact
    }
    val groups = ArrayList<CopyContact>()
    resolver.query(
        ContactsContract.Groups.CONTENT_URI,
        arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.NOTES,
        ),
        accountClause.selection,
        accountClause.args,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.Groups._ID)
        val titleIndex = cursor.getColumnIndex(ContactsContract.Groups.TITLE)
        val notesIndex = cursor.getColumnIndex(ContactsContract.Groups.NOTES)
        while (cursor.moveToNext()) {
            if (idIndex < 0 || cursor.isNull(idIndex)) continue
            val groupId = cursor.getLong(idIndex)
            val members = ArrayList<String>()
            for (rawId in memberships[groupId].orEmpty()) {
                val email = withLinks.firstOrNull { it.rawId == rawId }?.emails?.firstOrNull().orEmpty()
                if (email.isNotEmpty() && members.none { it.equals(email, ignoreCase = true) }) members.add(email)
            }
            groups.add(
                CopyContact(
                    group = true,
                    groupId = groupId,
                    displayName = cursorString(cursor, titleIndex),
                    note = cursorString(cursor, notesIndex),
                    members = members,
                ),
            )
        }
    }
    return withLinks + groups
}

private data class AccountClause(val selection: String, val args: Array<String>?)

private fun accountClause(accountType: String, accountName: String): AccountClause {
    val type = ContactsContract.RawContacts.ACCOUNT_TYPE
    val name = ContactsContract.RawContacts.ACCOUNT_NAME
    return if (accountType.isEmpty() && accountName.isEmpty()) {
        AccountClause("($type IS NULL OR $type='') AND ($name IS NULL OR $name='')", null)
    } else {
        AccountClause("$type=? AND $name=?", arrayOf(accountType, accountName))
    }
}

private fun cursorString(cursor: Cursor, index: Int): String {
    if (index < 0 || cursor.isNull(index)) return ""
    return cursor.getString(index) ?: ""
}

private fun cursorInt(cursor: Cursor, index: Int): Int {
    if (index < 0 || cursor.isNull(index)) return 0
    return cursor.getInt(index)
}

private fun cursorLong(cursor: Cursor, index: Int): Long {
    if (index < 0 || cursor.isNull(index)) return 0L
    return cursor.getLong(index)
}

private fun phoneLabel(type: Int, context: Context): String = when (type) {
    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> context.getString(R.string.copy_mobile)
    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> context.getString(R.string.copy_home)
    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> context.getString(R.string.copy_work)
    else -> context.getString(R.string.copy_phone)
}

private fun writeAndroidContacts(
    resolver: ContentResolver,
    accountType: String,
    accountName: String,
    entries: List<CopyContact>,
) {
    val emailToRaw = HashMap<String, Long>()
    for (entry in entries) {
        if (entry.group || entry.rawId <= 0L) continue
        for (email in entry.emails) emailToRaw[email.lowercase()] = entry.rawId
    }
    for (entry in entries) {
        if (entry.group || !entry.changed) continue
        val rawId = if (entry.rawId > 0L) {
            clearMappedRows(resolver, entry.rawId)
            insertMappedRows(resolver, entry.rawId, entry)
            entry.rawId
        } else {
            insertRawContact(resolver, accountType, accountName, entry)
        }
        if (rawId <= 0L) continue
        for (email in entry.emails) emailToRaw[email.lowercase()] = rawId
    }
    for (entry in entries) {
        if (!entry.group || !entry.changed) continue
        val groupId = if (entry.groupId > 0L) {
            entry.groupId
        } else {
            insertGroup(resolver, accountType, accountName, entry.displayName)
        }
        if (groupId <= 0L) continue
        for (email in entry.members) {
            val rawId = emailToRaw[email.lowercase()] ?: continue
            insertMembership(resolver, rawId, groupId)
        }
    }
}

private fun insertRawContact(
    resolver: ContentResolver,
    accountType: String,
    accountName: String,
    entry: CopyContact,
): Long {
    val values = ContentValues()
    putAccount(values, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, accountType, accountName)
    val uri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, values) ?: return 0L
    val rawId = ContentUris.parseId(uri)
    insertMappedRows(resolver, rawId, entry)
    return rawId
}

private fun clearMappedRows(resolver: ContentResolver, rawId: Long) {
    resolver.delete(
        ContactsContract.Data.CONTENT_URI,
        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (?,?,?,?)",
        arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        ),
    )
}

private fun insertMappedRows(resolver: ContentResolver, rawId: Long, entry: CopyContact) {
    if (entry.displayName.isNotEmpty() || entry.givenName.isNotEmpty() || entry.familyName.isNotEmpty()) {
        val values = ContentValues()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        if (entry.displayName.isNotEmpty()) {
            values.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, entry.displayName)
        }
        if (entry.givenName.isNotEmpty()) {
            values.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, entry.givenName)
        }
        if (entry.familyName.isNotEmpty()) {
            values.put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, entry.familyName)
        }
        resolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }
    if (entry.nickname.isNotEmpty()) {
        val values = ContentValues()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
        values.put(ContactsContract.CommonDataKinds.Nickname.NAME, entry.nickname)
        resolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }
    for (email in entry.emails) {
        if (email.isEmpty()) continue
        val values = ContentValues()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
        values.put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
        values.put(
            ContactsContract.CommonDataKinds.Email.TYPE,
            ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
        )
        resolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }
    if (entry.note.isNotEmpty()) {
        val values = ContentValues()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
        values.put(ContactsContract.CommonDataKinds.Note.NOTE, entry.note)
        resolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }
}

private fun insertGroup(
    resolver: ContentResolver,
    accountType: String,
    accountName: String,
    title: String,
): Long {
    val values = ContentValues()
    putAccount(values, ContactsContract.Groups.ACCOUNT_TYPE, ContactsContract.Groups.ACCOUNT_NAME, accountType, accountName)
    values.put(ContactsContract.Groups.TITLE, title)
    val uri = resolver.insert(ContactsContract.Groups.CONTENT_URI, values) ?: return 0L
    return ContentUris.parseId(uri)
}

private fun insertMembership(resolver: ContentResolver, rawId: Long, groupId: Long) {
    val values = ContentValues()
    values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
    values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
    values.put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
    resolver.insert(ContactsContract.Data.CONTENT_URI, values)
}

private fun putAccount(values: ContentValues, typeKey: String, nameKey: String, accountType: String, accountName: String) {
    if (accountType.isEmpty()) values.putNull(typeKey) else values.put(typeKey, accountType)
    if (accountName.isEmpty()) values.putNull(nameKey) else values.put(nameKey, accountName)
}
