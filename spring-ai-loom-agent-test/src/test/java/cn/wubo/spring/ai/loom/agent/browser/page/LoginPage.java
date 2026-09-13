package cn.wubo.spring.ai.loom.agent.browser.page;

import com.microsoft.playwright.Page;

/** login.html Page Object(选择器原样抄自 login.html/login.js)。 */
public class LoginPage {
    private final Page page;

    public LoginPage(Page page) { this.page = page; }

    public LoginPage open(String baseUrl) {
        page.navigate(baseUrl + "/spring/ai/loom/login.html");
        page.waitForSelector("#login-form");
        return this;
    }

    public void submit(String username, String password) {
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("#submit-btn");
    }

    public void expectRedirectToIndex() {
        page.waitForURL("**/spring/ai/loom/index.html",
                new Page.WaitForURLOptions().setTimeout(10_000));
    }

    public boolean errorVisible() { return page.isVisible("#error-msg"); }

    public String errorMessage() { return page.textContent("#error-msg"); }
}
