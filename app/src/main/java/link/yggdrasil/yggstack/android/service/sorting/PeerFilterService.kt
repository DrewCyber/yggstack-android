package link.yggdrasil.yggstack.android.service.sorting

import link.yggdrasil.yggstack.android.data.peer.PeerProtocol
import link.yggdrasil.yggstack.android.data.peer.PublicPeer

/**
 * Service for filtering peers by various criteria
 */
class PeerFilterService {
    
    /**
     * Filter peers by protocols
     */
    fun filterByProtocols(
        peers: List<PublicPeer>,
        protocols: List<PeerProtocol>
    ): List<PublicPeer> {
        if (protocols.isEmpty()) return peers
        return peers.filter { it.protocol in protocols }
    }
    
    /**
     * Filter peers by country
     */
    fun filterByCountry(
        peers: List<PublicPeer>,
        country: String
    ): List<PublicPeer> {
        return peers.filter { it.country.equals(country, ignoreCase = true) }
    }
    
    /**
     * Filter peers by countries
     */
    fun filterByCountries(
        peers: List<PublicPeer>,
        countries: List<String>
    ): List<PublicPeer> {
        if (countries.isEmpty()) return peers
        val lowercaseCountries = countries.map { it.lowercase() }
        return peers.filter { it.country.lowercase() in lowercaseCountries }
    }
    
    /**
     * Filter only checked peers (have ping or connect data)
     */
    fun filterChecked(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.filter { it.isChecked() }
    }
    
    /**
     * Filter only reachable peers (have positive RTT)
     */
    fun filterReachable(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.filter { it.getBestRtt() != null }
    }
    
    /**
     * Filter manually added peers
     */
    fun filterManual(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.filter { it.isManuallyAdded }
    }
    
    /**
     * Filter auto-discovered peers (not manual)
     */
    fun filterAutoDiscovered(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.filter { !it.isManuallyAdded }
    }
    
    /**
     * Filter peers by maximum RTT threshold
     */
    fun filterByMaxRtt(
        peers: List<PublicPeer>,
        maxRttMs: Long
    ): List<PublicPeer> {
        return peers.filter { peer ->
            val rtt = peer.getBestRtt()
            rtt != null && rtt <= maxRttMs
        }
    }
    
    /**
     * Get all unique countries from peer list
     */
    fun getCountries(peers: List<PublicPeer>): List<String> {
        return peers.map { it.country }.distinct().sorted()
    }
    
    /**
     * Get all unique protocols from peer list
     */
    fun getProtocols(peers: List<PublicPeer>): List<PeerProtocol> {
        return peers.map { it.protocol }.distinct().sortedBy { it.getPriority() }
    }
    
    /**
     * Group peers by country
     */
    fun groupByCountry(peers: List<PublicPeer>): Map<String, List<PublicPeer>> {
        return peers.groupBy { it.country }
    }
    
    /**
     * Group peers by protocol
     */
    fun groupByProtocol(peers: List<PublicPeer>): Map<PeerProtocol, List<PublicPeer>> {
        return peers.groupBy { it.protocol }
    }
}
