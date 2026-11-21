package com.athnasia.swarmwatchsvc.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "NANDA_COMPUTER_SMOKE", matches = "true")
class ComputerBrowserSmokeTest {
    @Test
    void realChromiumRendersInternalPageAndTypesWithoutNetworkOrUserProfile() throws Exception {
        try (ComputerExecutor executor = new PlaywrightComputerExecutor()) {
            ComputerExecutor.Result first = executor.execute("fixture-tenant", "fixture-agent", Map.of("actions", List.of(Map.of("type", "screenshot"))));
            assertEquals("about:blank", first.currentUrl());
            byte[] png = Base64.getDecoder().decode(first.screenshotDataUrl().substring("data:image/png;base64,".length()));
            var image = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(1280, image.getWidth());
            assertEquals(800, image.getHeight());
            ComputerExecutor.Result typed = executor.execute("fixture-tenant", "fixture-agent", Map.of("actions", List.of(
                    Map.of("type", "keypress", "keys", List.of("CTRL", "L")),
                    Map.of("type", "type", "text", "https://example.com"))));
            assertEquals("about:blank", typed.currentUrl());
            assertNotEquals(first.screenshotDataUrl(), typed.screenshotDataUrl());
        }
    }
}
