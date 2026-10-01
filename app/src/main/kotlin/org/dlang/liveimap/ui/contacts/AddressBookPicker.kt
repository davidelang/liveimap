package org.dlang.liveimap.ui.contacts

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.dlang.liveimap.session.SelectedAddress

@Suppress("UNUSED_PARAMETER")
@Composable
fun AddressBookPicker(onPicked: (SelectedAddress) -> Unit, onDismiss: () -> Unit) {
    Text("Address book")
}
