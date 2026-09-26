package link.yggdrasil.yggstack.android.data

/**
 * Generates the Proxy Auto-Configuration script served by [link.yggdrasil.yggstack.android.service.PacServer].
 *
 * The script uses string matching only (shExpMatch/dnsDomainIs plus plain JS
 * helpers): Android's PAC engine implements dnsResolve unreliably, and these
 * rules must never depend on DNS.
 */
object PacGenerator {

    /** Path the PAC server serves the script at. */
    const val PAC_PATH = "/proxy.pac"

    /** The PAC URL for the given listen address, as configured into Wi-Fi settings. */
    fun pacUrl(ip: String, port: Int): String = "http://$ip:$port$PAC_PATH"

    /**
     * Build the PAC script for the given config.
     *
     * - Ygg-only mode (default): hosts under `.ygg` (which covers `.pk.ygg`)
     *   and literal Yggdrasil IPv6 addresses (`http://[200:...]/`,
     *   `http://[308:...]/`) go through the proxy chain, everything else is
     *   DIRECT — so Android's connectivity validation reaches the internet
     *   directly and the Wi-Fi network stays validated.
     * - All-traffic mode: everything except localhost / IP literals / private
     *   ranges goes through the proxy (for DNS64+NAT64 global-internet setups).
     *
     * The proxy chain lists the HTTP proxy first, then SOCKS when enabled,
     * with no DIRECT fallback — a `.ygg` name cannot resolve via system DNS
     * anyway, and falling back would only add a resolver timeout.
     */
    fun generate(config: YggstackConfig): String {
        val chain = proxyChain(config)
        if (chain.isEmpty()) {
            // No usable proxy: serve an all-DIRECT script rather than a
            // broken one, so a stale PAC URL degrades gracefully.
            return header() + "function FindProxyForURL(url, host) {\n    return \"DIRECT\";\n}\n"
        }

        val proxyRules = if (config.pacAllTraffic) {
            buildString {
                appendLine("function FindProxyForURL(url, host) {")
                appendLine("    if (isPlainHostName(host) ||")
                appendLine("        shExpMatch(host, \"localhost\") ||")
                appendLine("        shExpMatch(host, \"127.*\") || isLoopbackLiteral(host) ||")
                appendLine("        shExpMatch(host, \"10.*\") || shExpMatch(host, \"192.168.*\") ||")
                appendLine("        shExpMatch(host, \"172.16.*\") || shExpMatch(host, \"172.17.*\") ||")
                appendLine("        shExpMatch(host, \"172.18.*\") || shExpMatch(host, \"172.19.*\") ||")
                appendLine("        shExpMatch(host, \"172.2?.*\") || shExpMatch(host, \"172.30.*\") ||")
                appendLine("        shExpMatch(host, \"172.31.*\") || shExpMatch(host, \"169.254.*\")) {")
                appendLine("        return \"DIRECT\";")
                appendLine("    }")
                appendLine("    return \"$chain\";")
                appendLine("}")
            }
        } else {
            buildString {
                appendLine("function FindProxyForURL(url, host) {")
                appendLine("    if (dnsDomainIs(host, \".ygg\") || isYggLiteralHost(host)) {")
                appendLine("        return \"$chain\";")
                appendLine("    }")
                appendLine("    return \"DIRECT\";")
                appendLine("}")
            }
        }
        return header() + helpers() + proxyRules
    }

    /**
     * Plain-JS host classifiers shared by the script's rules. shExpMatch can't
     * be used for IPv6 literals: `[` / `]` are character-class syntax in shell
     * patterns, so bracketed hosts are unexpressible, and PAC engines differ
     * in whether they pass IPv6 hosts bracketed — these helpers tolerate both
     * forms. Matches Yggdrasil's address space: 200::/7 node addresses
     * (hextet `200:`/`201:`) and 300::/8 routed subnets (hextet `300:`–`3ff:`,
     * where the public DNS servers like `308:62:45:62::` live).
     */
    private fun helpers(): String = """
        function isHexDigit(c) {
            return (c >= "0" && c <= "9") || (c >= "a" && c <= "f") || (c >= "A" && c <= "F");
        }
        function isYggLiteralHost(host) {
            var h = String(host);
            if (h.indexOf(":") < 0) return false;
            if (h.charAt(0) == "[" && h.charAt(h.length - 1) == "]") {
                h = h.substring(1, h.length - 1);
            }
            if (h.indexOf("200:", 0) == 0 || h.indexOf("201:", 0) == 0) return true;
            // 300::/8: first hextet 0x0300-0x03ff, i.e. "3" + two hex digits + ":"
            return h.length > 3 && h.charAt(0) == "3" &&
                isHexDigit(h.charAt(1)) && isHexDigit(h.charAt(2)) && h.charAt(3) == ":";
        }
        function isLoopbackLiteral(host) {
            var h = String(host);
            if (h.charAt(0) == "[" && h.charAt(h.length - 1) == "]") {
                h = h.substring(1, h.length - 1);
            }
            return h == "::1";
        }
    """.trimIndent() + "\n\n"

    /**
     * The PAC proxy chain from the enabled proxies, e.g.
     * `PROXY 127.0.0.1:8080; SOCKS 127.0.0.1:1080`. Empty when neither
     * proxy is usable. The PAC is evaluated on-device, so wildcard listen
     * addresses are normalized to loopback.
     */
    private fun proxyChain(config: YggstackConfig): String {
        val parts = mutableListOf<String>()
        if (config.proxyEnabled && config.httpEnabled && config.httpProxy.isNotBlank()) {
            pacHostPort(config.httpProxy, 8080)?.let { parts += "PROXY $it" }
        }
        if (config.proxyEnabled && config.socksEnabled && config.socksProxy.isNotBlank()) {
            pacHostPort(config.socksProxy, 1080)?.let { parts += "SOCKS $it" }
        }
        return parts.joinToString("; ")
    }

    /** Normalize `host:port` for on-device evaluation; null when unparseable. */
    private fun pacHostPort(address: String, defaultPort: Int): String? {
        val host = address.substringBeforeLast(':', address).removePrefix("[").removeSuffix("]")
        val port = address.substringAfterLast(':', "")
            .toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        val pacHost = when (host) {
            "", "::", "0.0.0.0" -> "127.0.0.1"
            else -> host
        }
        return "$pacHost:$port"
    }

    private fun header(): String =
        "// Generated by Yggstack — the app regenerates this on every start; edit settings in the app, not here.\n"
}
