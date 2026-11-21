package com.athnasia.swarmwatchsvc.tools;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.Mouse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class PlaywrightBrowser implements BrowserSession.Factory {
    private final PublicUrlPolicy policy;
    private Playwright playwright;
    private Browser browser;
    private PublicBrowserProxy proxy;

    PlaywrightBrowser(PublicUrlPolicy policy) { this.policy = policy; }

    @Override
    public BrowserSession open() {
        if (browser == null) {
            try {
                proxy = new PublicBrowserProxy(policy);
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                        .setChromiumSandbox(true).setTimeout(10000).setProxy(new Proxy("http://127.0.0.1:" + proxy.port()))
                        .setArgs(List.of("--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--disable-quic", "--proxy-bypass-list=<-loopback>")));
            } catch (Exception unavailable) {
                close();
                throw new ComputerUseException("The isolated Chromium browser is unavailable. Run ./gradlew playwrightInstall and install the required browser system libraries before retrying.");
            }
        }
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(ComputerAction.WIDTH, ComputerAction.HEIGHT).setAcceptDownloads(false)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        try {
            context.clearPermissions();
            context.route("**/*", route -> {
                String url = route.request().url();
                boolean embedded = !route.request().isNavigationRequest() && (url.startsWith("data:") || url.startsWith("blob:"));
                if (embedded || policy.allowed(url)) route.resume();
                else route.abort("blockedbyclient");
            });
            context.routeWebSocket("**/*", socket -> socket.close());
            context.addInitScript("""
                    Object.defineProperty(navigator, 'clipboard', {get: () => undefined});
                    Object.defineProperty(navigator, 'credentials', {get: () => undefined});
                    window.RTCPeerConnection = undefined;
                    window.webkitRTCPeerConnection = undefined;
                    window.showOpenFilePicker = undefined;
                    window.showSaveFilePicker = undefined;
                    window.showDirectoryPicker = undefined;
                    const originalExecCommand = Document.prototype.execCommand;
                    Document.prototype.execCommand = function(command, ...args) {
                      if (['copy','cut','paste'].includes(String(command).toLowerCase())) return false;
                      return originalExecCommand.call(this, command, ...args);
                    };
                    """);
            Page page = context.newPage();
            page.setDefaultTimeout(5000);
            page.setDefaultNavigationTimeout(10000);
            page.onDownload(download -> download.cancel());
            page.onDialog(dialog -> dialog.dismiss());
            page.onFileChooser(chooser -> chooser.setFiles(new Path[0]));
            context.onPage(popup -> popup.close());
            Session session = new Session(context, page, policy);
            session.home();
            return session;
        } catch (RuntimeException failed) {
            context.close();
            throw failed;
        }
    }

    @Override
    public void close() {
        if (browser != null) { browser.close(); browser = null; }
        if (playwright != null) { playwright.close(); playwright = null; }
        if (proxy != null) { proxy.close(); proxy = null; }
    }

    static final class Session implements BrowserSession {
        private final BrowserContext context;
        private final Page page;
        private final PublicUrlPolicy policy;

        Session(BrowserContext context, Page page, PublicUrlPolicy policy) {
            this.context = context;
            this.page = page;
            this.policy = policy;
        }

        @Override
        public void perform(ComputerAction action) {
            checkPage();
            if (action.type().equals("keypress")) {
                if (addressShortcut(action.keys())) {
                    home();
                    page.locator("#address").focus();
                } else if (action.keys().contains("Alt") && action.keys().contains("Home")) home();
                else page.keyboard().press(String.join("+", action.keys()));
            } else {
                for (String key : action.keys()) page.keyboard().down(key);
                try {
                    switch (action.type()) {
                        case "click" -> {
                            if (action.button().equals("back")) page.goBack();
                            else if (action.button().equals("forward")) page.goForward();
                            else page.mouse().click(action.x(), action.y(), new Mouse.ClickOptions().setButton(button(action.button())));
                        }
                        case "double_click" -> page.mouse().dblclick(action.x(), action.y());
                        case "move" -> page.mouse().move(action.x(), action.y());
                        case "scroll" -> { page.mouse().move(action.x(), action.y()); page.mouse().wheel(action.scrollX(), action.scrollY()); }
                        case "drag" -> {
                            var start = action.path().getFirst();
                            page.mouse().move(start.x(), start.y());
                            page.mouse().down();
                            try { for (var point : action.path().subList(1, action.path().size())) page.mouse().move(point.x(), point.y()); }
                            finally { page.mouse().up(); }
                        }
                        case "type" -> page.keyboard().insertText(action.text());
                        case "wait" -> page.waitForTimeout(1000);
                        case "screenshot" -> { }
                        default -> throw new ComputerUseException("Unsupported browser action.");
                    }
                } finally {
                    for (int index = action.keys().size() - 1; index >= 0; index--) page.keyboard().up(action.keys().get(index));
                }
            }
            checkPage();
        }

        private static MouseButton button(String button) {
            return switch (button) { case "right" -> MouseButton.RIGHT; case "middle", "wheel" -> MouseButton.MIDDLE; default -> MouseButton.LEFT; };
        }

        private static boolean addressShortcut(List<String> keys) {
            return keys.size() == 2 && (keys.contains("Control") || keys.contains("Meta"))
                    && keys.stream().anyMatch(key -> key.equalsIgnoreCase("L"));
        }

        private void checkPage() {
            if (page.isClosed()) throw new ComputerUseException("The isolated browser page closed. Start a fresh computer session.");
            String url = page.url();
            if (!url.equals("about:blank") && !policy.allowed(url)) {
                throw new ComputerUseException("Navigation to files, private networks, or non-public destinations is blocked.");
            }
        }

        void home() {
            page.navigate("about:blank");
            page.setContent("""
                    <!doctype html><html lang="en"><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Isolated agent browser</title>
                    <style>body{font:20px system-ui;background:#edf2f8;color:#172b46;margin:100px auto;max-width:950px}h1{font-size:42px}label{display:block;margin-top:30px}input{font:inherit;padding:15px;width:650px;border:1px solid #94a3b8;border-radius:6px}button{font:inherit;padding:15px;border:0;border-radius:6px;background:#365be8;color:white}p{line-height:1.6;color:#475569}small{font-size:16px}</style>
                    <h1>Isolated agent browser</h1><p>Open a public website or search the web. This browser has no access to your owner's accounts, files, or clipboard.</p>
                    <form id="open"><label for="address">Website address</label><input id="address" type="url" placeholder="https://example.com" required autofocus><button>Open URL</button></form>
                    <p id="notice" aria-live="polite"></p>
                    <form action="https://www.google.com/search" method="get"><label for="search">Web search</label><input id="search" name="q" placeholder="Search public information"><button>Search</button></form>
                    <p><small>Ctrl+L or Command+L opens the address field. Alt+Home returns here. Downloads and private network access are disabled.</small></p>
                    <script>document.getElementById('open').addEventListener('submit',event=>{event.preventDefault();try{const url=new URL(document.getElementById('address').value);if(!['http:','https:'].includes(url.protocol)||url.username||url.password)throw new Error();location.assign(url.href)}catch(error){document.getElementById('notice').textContent='Enter a public HTTP or HTTPS URL without credentials.'}});</script>
                    </html>
                    """);
        }

        @Override
        public byte[] screenshot() { checkPage(); return page.screenshot(new Page.ScreenshotOptions().setFullPage(false).setTimeout(5000)); }
        @Override
        public String currentUrl() { return page.url(); }
        @Override
        public void close() { context.close(); }
    }
}
