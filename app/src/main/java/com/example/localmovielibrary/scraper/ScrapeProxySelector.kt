package com.example.localmovielibrary.scraper

import com.example.localmovielibrary.data.repository.AppSettingsRepository
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * Reads the saved scrape proxy for every new connection, so changes take effect
 * without recreating the scraper or restarting the app.
 */
class ScrapeProxySelector(
    private val settingsRepository: AppSettingsRepository
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        listOf(settingsRepository.getScrapeProxy() ?: Proxy.NO_PROXY)

    override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: IOException?) = Unit
}
