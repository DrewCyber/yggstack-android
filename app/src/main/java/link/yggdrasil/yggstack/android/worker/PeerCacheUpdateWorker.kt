package link.yggdrasil.yggstack.android.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import link.yggdrasil.yggstack.android.data.peer.PeerDatabase
import link.yggdrasil.yggstack.android.data.peer.PeerRepository
import link.yggdrasil.yggstack.android.service.discovery.PeerDiscoveryService
import java.util.concurrent.TimeUnit

/**
 * Worker for periodic peer cache updates
 * Runs once every 24 hours
 */
class PeerCacheUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting peer cache update")
        
        return try {
            val discoveryService = PeerDiscoveryService(applicationContext)
            val peerRepository = PeerRepository(applicationContext)
            
            val result = discoveryService.fetchPublicPeers()
            
            result.fold(
                onSuccess = { peers ->
                    Log.d(TAG, "Fetched ${peers.size} peers, updating cache")
                    peerRepository.insertPeers(peers)
                    Log.d(TAG, "Peer cache update successful")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Peer cache update failed", error)
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Peer cache update exception", e)
            Result.failure()
        }
    }
    
    companion object {
        private const val TAG = "PeerCacheUpdateWorker"
        private const val WORK_NAME = "peer_cache_update"
        
        /**
         * Schedule periodic peer cache updates
         */
        fun schedulePeriodicUpdate(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeerCacheUpdateWorker>(
                24, TimeUnit.HOURS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            
            Log.d(TAG, "Scheduled periodic peer cache update")
        }
        
        /**
         * Cancel periodic updates
         */
        fun cancelPeriodicUpdate(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic peer cache update")
        }
    }
}
