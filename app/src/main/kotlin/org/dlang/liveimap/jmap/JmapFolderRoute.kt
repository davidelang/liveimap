package org.dlang.liveimap.jmap

fun folderOfferOrImap(offer: () -> JmapOffer): JmapOffer {
    return try {
        offer()
    } catch (_: JmapFailure) {
        JmapOffer.None
    }
}

fun jmapFolderRoute(offer: JmapOffer, username: String): Boolean {
    return offer is JmapOffer.Mail && username.isNotBlank()
}
