package cn.wubo.spring.ai.loom.agent.browser.page;

import com.microsoft.playwright.Page;

/** index.html Page Object(仅常用入口,细粒度选择器测试内直接用)。 */
public class IndexPage {
    private final Page page;

    public IndexPage(Page page) { this.page = page; }

    public IndexPage open(String baseUrl) {
        page.navigate(baseUrl + "/spring/ai/loom/index.html");
        expectLoaded();
        return this;
    }

    public void expectLoaded() {
        page.waitForSelector("#textarea",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    public void send(String text) {
        page.fill("#textarea", text);
        page.click("#send-btn");
    }
}
