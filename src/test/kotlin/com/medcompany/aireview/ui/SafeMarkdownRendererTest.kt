package com.medcompany.aireview.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SafeMarkdownRendererTest {
    @Test
    fun `renders common review formatting`() {
        val html = SafeMarkdownRenderer.render(
            """
            ## 结论

            - **高风险**：检查 `transfer.getState()`
            - 建议补充测试
            """.trimIndent(),
        )

        assertContains(html, "<h2>结论</h2>")
        assertContains(html, "<ul><li><strong>高风险</strong>：检查 <code>transfer.getState()</code></li>")
        assertContains(html, "<li>建议补充测试</li></ul>")
    }

    @Test
    fun `escapes model supplied html`() {
        val html = SafeMarkdownRenderer.render("<script>alert('x')</script> **仍然加粗**")

        assertContains(html, "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
        assertContains(html, "<strong>仍然加粗</strong>")
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `renders and escapes fenced code`() {
        val html = SafeMarkdownRenderer.render(
            """
            ```java
            if (a < b && enabled) {
                run();
            }
            ```
            """.trimIndent(),
        )

        assertContains(html, "<pre><code>if (a &lt; b &amp;&amp; enabled) {")
        assertContains(html, "    run();")
        assertContains(html, "</code></pre>")
    }

    @Test
    fun `renders ordered lists and quotes`() {
        val html = SafeMarkdownRenderer.render("1. 先复现\n2. 再修复\n\n> 注意回归")

        assertContains(html, "<ol><li>先复现</li><li>再修复</li></ol>")
        assertContains(html, "<blockquote>注意回归</blockquote>")
    }
}
