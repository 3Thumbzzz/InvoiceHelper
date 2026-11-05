package com.tententen.invoicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMergeTest {
    private fun data(
        companies: List<Company> = emptyList(),
        deleted: Map<String, Long> = emptyMap(),
        profile: Profile = Profile(name = "x"),
        history: List<SentRecord> = emptyList(),
    ) = SyncData(companies, deleted, profile, history)

    private val mec = Company(id = "b-id", name = "Melbourne Entertainment Company", code = "MEC", nextInvoiceNo = 74)

    @Test
    fun matchesByCodeAcrossDevicesAndKeepsHigherNumber() {
        val local = data(listOf(mec.copy(id = "z-local", updatedAt = 0)))
        val remote = data(listOf(mec.copy(id = "a-remote", email = "new@x.com", nextInvoiceNo = 80, updatedAt = 5)))
        val m = SyncMerge.merge(local, remote)
        assertEquals(1, m.companies.size)
        assertEquals("a-remote", m.companies[0].id)          // both devices settle on the smaller id
        assertEquals(80, m.companies[0].nextInvoiceNo)
        assertEquals("new@x.com", m.companies[0].email)      // newer edit wins
    }

    @Test
    fun newerLocalEditWinsButNumberStillMax() {
        val local = data(listOf(mec.copy(email = "local@x.com", nextInvoiceNo = 75, updatedAt = 20)))
        val remote = data(listOf(mec.copy(email = "remote@x.com", nextInvoiceNo = 77, updatedAt = 10)))
        val c = SyncMerge.merge(local, remote).companies.single()
        assertEquals("local@x.com", c.email)
        assertEquals(77, c.nextInvoiceNo)
    }

    @Test
    fun deletionsSyncUnlessEditedAfterwards() {
        val php = Company(id = "php", name = "Party Hire Productions", code = "PHP", updatedAt = 5)
        val removed = SyncMerge.merge(data(listOf(mec, php)), data(listOf(mec), deleted = mapOf("php" to 10)))
        assertTrue(removed.companies.none { it.id == "php" })
        val kept = SyncMerge.merge(
            data(listOf(mec, php.copy(updatedAt = 50))),
            data(listOf(mec), deleted = mapOf("php" to 10)),
        )
        assertTrue(kept.companies.any { it.id == "php" })
    }

    @Test
    fun profileNewestWinsAndHistoryIsUnioned() {
        val a = SentRecord("MEC", 74, "A", 1.0, "1/Nov/2026", "x", sentAt = 1)
        val b = SentRecord("MEC", 75, "B", 1.0, "2/Nov/2026", "x", sentAt = 2)
        val m = SyncMerge.merge(
            data(profile = Profile(name = "old", updatedAt = 1), history = listOf(a)),
            data(profile = Profile(name = "new", updatedAt = 9), history = listOf(b, a)),
        )
        assertEquals("new", m.profile.name)
        assertEquals(listOf(b, a), m.history)
    }

    @Test
    fun jsonRoundTripDropsGoogleAccount() {
        val d = data(
            listOf(mec.copy(updatedAt = 3)), mapOf("gone" to 4L),
            Profile(name = "Test DJ", googleAccount = "me@example.com", updatedAt = 2),
            listOf(SentRecord("MEC", 74, "A", 490.0, "1/Nov/2026", "x", 7)),
        )
        val back = SyncData.fromJson(d.toJson())
        assertEquals(d.companies, back.companies)
        assertEquals(d.deleted, back.deleted)
        assertEquals("", back.profile.googleAccount)
        assertEquals(d.history, back.history)
    }
}
