package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class AppLabelIndexTest {
    @Test fun repeatedActivitiesFromOnePackageAreUnambiguous() {
        val index = AppLabelIndex(listOf("QQ" to "com.tencent.mobileqq", "QQ" to "com.tencent.mobileqq"))
        repeat(10) { assertEquals("com.tencent.mobileqq", index.find("QQ")) }
    }
    @Test fun duplicateNamesFromDifferentPackagesMustNotChooseOne() {
        val index = AppLabelIndex(listOf("Reader" to "reader.one", "Reader" to "reader.two"))
        assertNull(index.find("Reader"))
        assertNull(index.find("Missing"))
    }
    @Test fun badgesAndRunningStateResolveToTheApplicationTitle() {
        val index = AppLabelIndex(listOf("微信" to "com.tencent.mm", "OneTunnel" to "dev.dsh.onetunnel"))
        assertEquals("com.tencent.mm", index.find("“微信”有 2 条通知"))
        assertEquals("dev.dsh.onetunnel", index.find("OneTunnel 活跃"))
    }
    @Test fun rebuiltIndexDoesNotReuseAnOldPackageMapping() {
        val before = AppLabelIndex(listOf("Reader" to "reader.old"))
        val after = AppLabelIndex(listOf("Reader" to "reader.new"))
        assertEquals("reader.old", before.find("Reader"))
        assertEquals("reader.new", after.find("Reader"))
    }
}
