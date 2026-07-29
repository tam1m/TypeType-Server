package dev.typetype.server

import dev.typetype.server.SubscriptionFeedTestFixtures.subscription
import dev.typetype.server.services.SubscriptionFeedPageResult
import dev.typetype.server.services.SubscriptionFeedService
import dev.typetype.server.services.SubscriptionsService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubscriptionFeedServiceTest {
    companion object { @BeforeAll @JvmStatic fun initDb() = TestDatabase.setup() }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `snapshots and cursors remain scoped to their user`() = runTest {
        val subscriptions = SubscriptionsService()
        subscriptions.add("user-a", subscription("https://example.com/a", "A"))
        subscriptions.add("user-b", subscription("https://example.com/b", "B"))
        val service = SubscriptionFeedService(subscriptions, FakeChannelService(), FakeCacheService())

        assertTrue(service.getPage("user-a", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        assertTrue(service.getPage("user-b", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        service.awaitRefresh("user-a")
        service.awaitRefresh("user-b")

        val pageA = service.getPage("user-a", 0, 30, null) as SubscriptionFeedPageResult.Ready
        val pageB = service.getPage("user-b", 0, 30, null) as SubscriptionFeedPageResult.Ready
        assertEquals(listOf("https://example.com/a/video"), pageA.response.videos.map { it.url })
        assertEquals(listOf("https://example.com/b/video"), pageB.response.videos.map { it.url })
    }

    @Test
    fun `publishes partial snapshot when some sources fail and previous snapshot exists`() = runTest {
        val subscriptions = SubscriptionsService()
        subscriptions.add("user", subscription("https://example.com/a", "A"))
        subscriptions.add("user", subscription("https://example.com/b", "B"))
        subscriptions.add("user", subscription("https://fail.example.com/c", "FAIL"))
        val cache = FakeCacheService()

        // First refresh: all channels succeed → snapshot with 3 videos
        val successService = SubscriptionFeedService(subscriptions, FakeChannelService(), cache)
        assertTrue(successService.getPage("user", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        successService.awaitRefresh("user")
        val first = successService.getPage("user", 0, 30, null) as SubscriptionFeedPageResult.Ready
        assertEquals(3, first.response.videos.size)

        // Second refresh: one channel fails → should publish partial snapshot with 2 videos
        val failService = SubscriptionFeedService(
            subscriptions,
            FakeChannelService(failUrls = setOf("https://fail.example.com/c")),
            cache,
        )
        failService.invalidate("user") // marks stale and schedules refresh
        failService.awaitRefresh("user")

        val second = failService.getPage("user", 0, 30, null) as SubscriptionFeedPageResult.Ready
        val videoUrls = second.response.videos.map { it.url }
        assertEquals(2, videoUrls.size)
        assertTrue(videoUrls.contains("https://example.com/a/video"))
        assertTrue(videoUrls.contains("https://example.com/b/video"))
    }

    @Test
    fun `rejects snapshot when all sources fail and no previous snapshot exists`() = runTest {
        val subscriptions = SubscriptionsService()
        subscriptions.add("user", subscription("https://fail.example.com/a", "A"))
        subscriptions.add("user", subscription("https://fail.example.com/b", "B"))
        val failUrls = setOf("https://fail.example.com/a", "https://fail.example.com/b")
        val service = SubscriptionFeedService(subscriptions, FakeChannelService(failUrls), FakeCacheService())

        assertTrue(service.getPage("user", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        service.awaitRefresh("user")
        // No snapshot should be published — all sources failed and no previous exists
        assertTrue(service.getPage("user", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
    }

    @Test
    fun `preserves previous snapshot when all sources fail`() = runTest {
        val subscriptions = SubscriptionsService()
        subscriptions.add("user", subscription("https://fail.example.com/a", "A"))
        subscriptions.add("user", subscription("https://fail.example.com/b", "B"))
        val cache = FakeCacheService()

        // First refresh with working channels → snapshot published
        val successService = SubscriptionFeedService(subscriptions, FakeChannelService(), cache)
        assertTrue(successService.getPage("user", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        successService.awaitRefresh("user")
        val first = successService.getPage("user", 0, 30, null) as SubscriptionFeedPageResult.Ready
        assertEquals(2, first.response.videos.size)

        // Second refresh with all failing channels → previous snapshot preserved
        val failUrls = setOf("https://fail.example.com/a", "https://fail.example.com/b")
        val failService = SubscriptionFeedService(subscriptions, FakeChannelService(failUrls), cache)
        failService.invalidate("user")
        failService.awaitRefresh("user")

        val second = failService.getPage("user", 0, 30, null) as SubscriptionFeedPageResult.Ready
        assertEquals(2, second.response.videos.size)
        assertEquals(first.response.videos.map { it.url }, second.response.videos.map { it.url })
    }

    @Test
    fun `publishes empty snapshot for user with no subscriptions`() = runTest {
        val subscriptions = SubscriptionsService()
        val service = SubscriptionFeedService(subscriptions, FakeChannelService(), FakeCacheService())

        assertTrue(service.getPage("user", 0, 30, null) is SubscriptionFeedPageResult.Preparing)
        service.awaitRefresh("user")

        val page = service.getPage("user", 0, 30, null) as SubscriptionFeedPageResult.Ready
        assertEquals(0, page.response.videos.size)
    }
}
