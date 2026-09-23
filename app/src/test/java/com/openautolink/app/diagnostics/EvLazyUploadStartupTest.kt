package com.openautolink.app.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EvLazyUploadStartupTest {
    @Test fun `destructive quiescence at installed barrier cleans prestart owner and lease`() = runBlocking {
        val queue = EvContributionQueue(Files.createTempDirectory("ev-prestart-delete").toFile())
        queue.append("drive", 1, """{"schema":2,"type":"vehicle","elapsedBucketS":0,"vehicleClass":"0123456789abcdef"}""", "owner")
        queue.close("drive", 2)
        val lifecycle = EvContributionLifecycleGate()
        val ownership = EvUploadJobOwnership<Job>()
        val uploadLease = lifecycle.admitUpload { true }!!
        val releases = AtomicInteger()
        var bodyRuns = 0
        var quiescence: EvUploadQuiescence.Result? = null

        val first = EvLazyUploadStartup.launch(
            scope = this,
            ownership = ownership,
            releaseLease = { releases.incrementAndGet(); lifecycle.releaseUpload(uploadLease) },
            afterOwnerInstalled = { exact ->
                val deletion = lifecycle.beginDestructiveOperation()
                assertTrue(queue.beginDeletion())
                quiescence = runBlocking {
                    EvUploadQuiescence.awaitExactOrOrphan(
                        ownership, exact, 1_000,
                        cancelExact = Job::cancel,
                        awaitExact = { job, _ -> job.join(); true },
                    )
                }
                val deleted = lifecycle.exclusive { queue.deleteAllArtifacts() }
                assertTrue(queue.completeDeletion(deleted))
                lifecycle.finishDestructiveOperation(deletion, resumeAdmissions = true)
            },
        ) { bodyRuns++ }

        first!!.join()
        assertEquals(0, bodyRuns)
        assertEquals(1, releases.get())
        assertEquals(EvUploadQuiescence.Result(quiesced = true, orphaned = false), quiescence)
        assertNull(ownership.current())
        assertFalse(ownership.isOrphaned())
        assertEquals(0, queue.storageUsage().units)

        val replacementLease = lifecycle.admitUpload { true }!!
        var replacementRuns = 0
        val replacement = EvLazyUploadStartup.launch(
            scope = this,
            ownership = ownership,
            releaseLease = { lifecycle.releaseUpload(replacementLease) },
        ) { replacementRuns++ }
        replacement!!.join()
        assertEquals(1, replacementRuns)
        assertNull(ownership.current())
    }

    @Test fun `cancellation simultaneous with start releases once and clears exact owner`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val releases = AtomicInteger()
        val installed = CountDownLatch(1)
        val race = CountDownLatch(1)
        val launcher = async(Dispatchers.Default) {
            checkNotNull(EvLazyUploadStartup.launch(
                scope = CoroutineScope(Dispatchers.Default),
                ownership = ownership,
                releaseLease = { releases.incrementAndGet() },
                afterOwnerInstalled = {
                    installed.countDown()
                    assertTrue(race.await(5, TimeUnit.SECONDS))
                },
            ) { })
        }
        assertTrue(installed.await(5, TimeUnit.SECONDS))
        val canceller = thread { ownership.current()!!.cancel(); race.countDown() }
        val exact = launcher.await()
        canceller.join(5_000)
        assertFalse(canceller.isAlive)
        exact.join()

        assertEquals(1, releases.get())
        assertNull(ownership.current())
        assertFalse(ownership.isOrphaned())
    }

    @Test fun `normal completion releases once after owned body starts`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val releases = AtomicInteger()
        var installedBeforeBody = false
        var bodyRuns = 0
        val job = EvLazyUploadStartup.launch(
            scope = this,
            ownership = ownership,
            releaseLease = { releases.incrementAndGet() },
        ) {
            installedBeforeBody = ownership.current() === coroutineContext[Job]
            bodyRuns++
        }
        job!!.join()
        assertTrue(installedBeforeBody)
        assertEquals(1, bodyRuns)
        assertEquals(1, releases.get())
        assertNull(ownership.current())
    }

    @Test fun `failed install never runs candidate and cannot clear existing owner`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val incumbent = launch { kotlinx.coroutines.awaitCancellation() }
        assertTrue(ownership.tryInstall(incumbent))
        val releases = AtomicInteger()
        var bodyRuns = 0

        val candidate = EvLazyUploadStartup.launch(
            scope = this,
            ownership = ownership,
            releaseLease = { releases.incrementAndGet() },
        ) { bodyRuns++ }

        assertNull(candidate)
        assertEquals(0, bodyRuns)
        assertEquals(1, releases.get())
        assertSame(incumbent, ownership.current())
        ownership.finishDetailed(incumbent)
        incumbent.cancelAndJoin()
    }

    @Test fun `startup hook exception cancels candidate and releases exact ownership once`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val releases = AtomicInteger()
        var bodyRuns = 0

        val failure = assertThrows(IllegalStateException::class.java) {
            EvLazyUploadStartup.launch(
                scope = this,
                ownership = ownership,
                releaseLease = { releases.incrementAndGet() },
                afterOwnerInstalled = { throw IllegalStateException("startup failed") },
            ) { bodyRuns++ }
        }

        assertEquals("startup failed", failure.message)
        assertEquals(0, bodyRuns)
        assertEquals(1, releases.get())
        assertNull(ownership.current())
        assertFalse(ownership.isOrphaned())
    }

    @Test fun `stale completion cannot clear replacement owner`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val oldMayFinish = CountDownLatch(1)
        val old = launch(Dispatchers.Default) { oldMayFinish.await() }
        assertTrue(ownership.tryInstall(old))
        val oldCleanup = EvUploadOwnerCleanup(old, ownership, releaseLease = {})
        old.invokeOnCompletion { oldCleanup.complete() }

        assertTrue(ownership.finishDetailed(old).finished)
        val replacement = launch { kotlinx.coroutines.awaitCancellation() }
        assertTrue(ownership.tryInstall(replacement))
        oldMayFinish.countDown()
        old.join()

        assertSame(replacement, ownership.current())
        assertFalse(oldCleanup.complete().finished)
        ownership.finishDetailed(replacement)
        replacement.cancelAndJoin()
    }

    @Test fun `already completed exact owner is quiescent after completion callback`() = runBlocking {
        val ownership = EvUploadJobOwnership<Job>()
        val job = EvLazyUploadStartup.launch(this, ownership, releaseLease = {}) { }
        job!!.join()

        val result = EvUploadQuiescence.awaitExactOrOrphan(
            ownership, job, 1_000,
            cancelExact = Job::cancel,
            awaitExact = { exact, _ -> exact.join(); true },
        )
        assertEquals(EvUploadQuiescence.Result(quiesced = true, orphaned = false), result)
        assertFalse(ownership.isOrphaned())
    }
}
