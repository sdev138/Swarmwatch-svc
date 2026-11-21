package com.athnasia.swarmwatchsvc.tools;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.MouseButton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightBrowserTest {
    private final Page page = mock(Page.class);
    private final BrowserContext context = mock(BrowserContext.class);
    private final Mouse mouse = mock(Mouse.class);
    private final Keyboard keyboard = mock(Keyboard.class);
    private PlaywrightBrowser.Session session;

    @BeforeEach
    void setup() {
        when(page.mouse()).thenReturn(mouse);
        when(page.keyboard()).thenReturn(keyboard);
        when(page.url()).thenReturn("about:blank");
        session = new PlaywrightBrowser.Session(context, page, new PublicUrlPolicy());
    }

    @Test
    void mouseAndKeyboardActionsMapToActualPlaywrightOperations() {
        session.perform(action(Map.of("type", "click", "x", 10, "y", 20, "button", "right")));
        var click = ArgumentCaptor.forClass(Mouse.ClickOptions.class);
        verify(mouse).click(eq(10.0), eq(20.0), click.capture());
        assertEquals(MouseButton.RIGHT, click.getValue().button);
        session.perform(action(Map.of("type", "double_click", "x", 15, "y", 25)));
        verify(mouse).dblclick(15, 25);
        session.perform(action(Map.of("type", "type", "text", "penguin")));
        verify(keyboard).insertText("penguin");
        session.perform(action(Map.of("type", "keypress", "keys", List.of("CTRL", "A"))));
        verify(keyboard).press("Control+A");
        session.perform(action(Map.of("type", "scroll", "x", 30, "y", 40, "scroll_x", -50, "scroll_y", 300)));
        var order = inOrder(mouse);
        order.verify(mouse).move(30, 40);
        order.verify(mouse).wheel(-50, 300);
    }

    @Test
    void dragAndModifiersAlwaysReleaseEvenWhenPageRejectsMouseMovement() {
        session.perform(action(Map.of("type", "drag", "path", List.of(Map.of("x", 1, "y", 2), Map.of("x", 3, "y", 4)))));
        var order = inOrder(mouse);
        order.verify(mouse).move(1, 2);
        order.verify(mouse).down();
        order.verify(mouse).move(3, 4);
        order.verify(mouse).up();
        doThrow(new PlaywrightException("fixture rejected movement")).when(mouse).move(100, 200);
        assertThrows(PlaywrightException.class, () -> session.perform(action(Map.of("type", "move", "x", 100, "y", 200, "keys", List.of("SHIFT")))));
        verify(keyboard).down("Shift");
        verify(keyboard).up("Shift");
    }

    @Test
    void returnsPngBytesWithoutWritingScreenshotsToDiskAndClosesContext() {
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(new byte[]{(byte) 137, 80, 78, 71});
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71}, session.screenshot());
        var options = ArgumentCaptor.forClass(Page.ScreenshotOptions.class);
        verify(page).screenshot(options.capture());
        assertNull(options.getValue().path);
        session.close();
        verify(context).close();
    }

    private ComputerAction action(Map<String, Object> value) { return ComputerAction.batch(Map.of("actions", List.of(value))).getFirst(); }
}
