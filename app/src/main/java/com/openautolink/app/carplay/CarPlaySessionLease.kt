package com.openautolink.app.carplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exact attempt protection, separate from Surface and UI selection. */
interface CarPlaySessionLease {
    /** Worker-only bounded wait for actual startForeground success, not startService acceptance. */
    fun awaitReady() {}
    fun release() {}
}

fun interface CarPlaySessionLeaseSource {
    fun acquire(): CarPlaySessionLease
    companion object {
        val NONE = CarPlaySessionLeaseSource { object : CarPlaySessionLease {} }
    }
}

class CarPlaySessionLeases(private val context: Context) : CarPlaySessionLeaseSource {
    override fun acquire(): CarPlaySessionLease = Registry.acquire(context)

    companion object {
        fun forContext(context: Context): CarPlaySessionLeaseSource = CarPlaySessionLeases(context.applicationContext)
    }

    /** One process-local exact-token set. No saved owner, sticky restart, or Surface dependency. */
    internal object Registry {
        private class Lease(val id: Long) : CarPlaySessionLease {
            val ready = CompletableFuture<Unit>()
            var admitted = false // Guarded by lock; OS start acceptance precedes readiness.
            override fun awaitReady() { ready.get(10, TimeUnit.SECONDS) }
            override fun release() = releaseExact(this)
        }
        private val lock = Any()
        private val main = Handler(Looper.getMainLooper())
        private val leases = mutableMapOf<Long, Lease>()
        private var generation = 0L
        private var service: CarPlaySessionService? = null
        private var startId = 0
        private var foreground = false

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else check(main.post(action)) { "CarPlay foreground dispatch unavailable" }
        }

        fun acquire(context: Context): CarPlaySessionLease {
            val lease = synchronized(lock) { Lease(++generation).also { leases[it.id] = it } }
            try {
                onMain {
                    try {
                        synchronized(lock) {
                            if (leases[lease.id] !== lease) return@onMain
                        }
                        // Still request OS admission for each explicit attempt. Background-start
                        // restrictions/security failures must never be interpreted as protection.
                        context.startForegroundService(Intent(context, CarPlaySessionService::class.java)
                            .putExtra("attempt", lease.id)) ?: error("CarPlay foreground service unavailable")
                        synchronized(lock) {
                            if (leases[lease.id] === lease) {
                                lease.admitted = true
                                if (foreground && service != null) lease.ready.complete(Unit)
                            }
                        }
                    } catch (failure: Exception) {
                        lease.ready.completeExceptionally(failure)
                    }
                }
            } catch (failure: Exception) { lease.ready.completeExceptionally(failure) }
            return lease
        }

        private fun releaseExact(lease: Lease) {
            synchronized(lock) {
                if (leases[lease.id] !== lease) return
                leases.remove(lease.id)
                lease.ready.completeExceptionally(IllegalStateException("CarPlay attempt retired before foreground acknowledgement"))
            }
            onMain { synchronized(lock) { reconcileStop() } }
        }

        fun created(owner: CarPlaySessionService) = synchronized(lock) {
            service = owner
            foreground = false
            startId = 0
        }

        fun started(owner: CarPlaySessionService, id: Int) = synchronized(lock) {
            if (service !== owner) return@synchronized
            startId = id
            try {
                // Even a canceled, delayed FGS start must fulfill its OS promotion obligation.
                if (!foreground) { owner.promote(); foreground = true }
                leases.values.filter { it.admitted }.forEach { it.ready.complete(Unit) }
                reconcileStop()
            } catch (failure: Exception) {
                leases.values.forEach { it.ready.completeExceptionally(failure) }
                owner.stopSelfResult(id)
            }
        }

        private fun reconcileStop() {
            val owner = service ?: return
            if (leases.isNotEmpty() || startId == 0) return
            if (foreground) { owner.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE); foreground = false }
            owner.stopSelfResult(startId)
        }

        fun destroyed(owner: CarPlaySessionService) = synchronized(lock) {
            if (service !== owner) return@synchronized
            service = null
            foreground = false
            leases.values.forEach { it.ready.completeExceptionally(IllegalStateException("CarPlay foreground service destroyed")) }
        }
    }
}
