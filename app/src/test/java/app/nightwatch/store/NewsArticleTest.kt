package app.nightwatch.store
import org.junit.Assert.*
import org.junit.Test

class NewsArticleTest {
    @Test fun removesHiddenTeamRosters() {
        assertEquals(listOf("Team A won."), NewsArticleParser.paragraphs("""<div class="article-body"><p><span>Team A<span class="article-ref-card">Team A Rank 10 Player One</span></span> won.</p></div>"""))
    }
    @Test fun extractsBodyWithoutCommentsAndNestedDuplicates() {
        assertEquals(listOf("Title", "First paragraph", "Quoted"), NewsArticleParser.paragraphs("""<div class="article-body"><h2>Title</h2><p>First <a href="/">paragraph</a></p><blockquote><p>Quoted</p></blockquote><script>bad()</script></div><p>Comment</p>"""))
    }
    @Test(expected=IllegalStateException::class) fun rejectsMissingBody() { NewsArticleParser.paragraphs("<p>Cloudflare error</p>") }
    @Test(expected=IllegalArgumentException::class) fun rejectsForeignHost() { NewsArticleParser.path("https://evil.test/123/article") }
    @Test fun acceptsArticlePath() { assertEquals("/123/article", NewsArticleParser.path("https://www.vlr.gg/123/article")) }
}
