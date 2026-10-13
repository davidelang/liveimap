package org.dlang.liveimap.debug.acceptance

import androidx.compose.runtime.Composable

/** Release builds carry no acceptance runner (F46, size work); Settings only calls this when BuildConfig.DEBUG. */
@Composable
fun AcceptanceSection() {
}
