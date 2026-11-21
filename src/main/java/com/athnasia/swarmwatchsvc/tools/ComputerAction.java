package com.athnasia.swarmwatchsvc.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record ComputerAction(String type, int x, int y, String button, String text, int scrollX, int scrollY,
                      List<String> keys, List<Point> path) {
    static final int WIDTH = 1280;
    static final int HEIGHT = 800;
    private static final Set<String> TYPES = Set.of("click", "double_click", "move", "scroll", "drag", "keypress", "type", "wait", "screenshot");
    private static final Set<String> MODIFIERS = Set.of("Control", "Meta", "Alt", "Shift");
    record Point(int x, int y) { }

    static List<ComputerAction> batch(Map<String, Object> call) {
        Object checks = call.get("pending_safety_checks");
        if (checks != null && (!(checks instanceof List<?> list) || !list.isEmpty())) {
            throw new ComputerUseException("Computer use needs human approval for pending safety checks. No action was executed.");
        }
        if (!(call.get("actions") instanceof List<?> actions) || actions.size() > 25) {
            throw new ComputerUseException("Computer use requires an actions array with at most 25 actions.");
        }
        List<ComputerAction> parsed = new ArrayList<>();
        for (Object value : actions) {
            if (!(value instanceof Map<?, ?> action)) throw invalid();
            String type = string(action.get("type"));
            if (!TYPES.contains(type)) throw new ComputerUseException("Unsupported computer action. Only structured browser input is permitted.");
            int x = 0, y = 0;
            if (Set.of("click", "double_click", "move", "scroll").contains(type)) {
                x = coordinate(action.get("x"), WIDTH);
                y = coordinate(action.get("y"), HEIGHT);
            }
            String button = action.containsKey("button") ? string(action.get("button")) : "left";
            if (!Set.of("left", "right", "middle", "wheel", "back", "forward").contains(button)) throw invalid();
            List<String> keys = keys(action.get("keys"));
            if (!type.equals("keypress") && keys.stream().anyMatch(key -> !MODIFIERS.contains(key))) throw invalid();
            if (type.equals("keypress")) validateShortcut(keys);
            String text = "";
            if (type.equals("type")) {
                text = string(action.get("text"));
                if (text.length() > 20000) throw new ComputerUseException("Computer typing is limited to 20000 characters per action.");
            }
            int scrollX = type.equals("scroll") ? delta(action.get("scroll_x")) : 0;
            int scrollY = type.equals("scroll") ? delta(action.get("scroll_y")) : 0;
            List<Point> path = new ArrayList<>();
            if (type.equals("drag")) {
                if (!(action.get("path") instanceof List<?> points) || points.size() < 2 || points.size() > 200) throw invalid();
                for (Object point : points) {
                    if (!(point instanceof Map<?, ?> pair)) throw invalid();
                    path.add(new Point(coordinate(pair.get("x"), WIDTH), coordinate(pair.get("y"), HEIGHT)));
                }
            }
            parsed.add(new ComputerAction(type, x, y, button, text, scrollX, scrollY, List.copyOf(keys), List.copyOf(path)));
        }
        return List.copyOf(parsed);
    }

    private static List<String> keys(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values) || values.size() > 8) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object key : values) {
            String raw = string(key);
            String normalized = switch (raw.toUpperCase(Locale.ROOT)) {
                case "CTRL", "CONTROL" -> "Control";
                case "META", "CMD", "COMMAND", "SUPER" -> "Meta";
                case "SHIFT" -> "Shift";
                case "ALT", "OPTION" -> "Alt";
                case "ENTER", "RETURN" -> "Enter";
                case "ESC", "ESCAPE" -> "Escape";
                case "TAB" -> "Tab";
                case "SPACE" -> "Space";
                case "BACKSPACE" -> "Backspace";
                case "DELETE", "DEL" -> "Delete";
                case "INSERT" -> "Insert";
                case "HOME" -> "Home";
                case "END" -> "End";
                case "PAGEUP" -> "PageUp";
                case "PAGEDOWN" -> "PageDown";
                case "UP", "ARROWUP" -> "ArrowUp";
                case "DOWN", "ARROWDOWN" -> "ArrowDown";
                case "LEFT", "ARROWLEFT" -> "ArrowLeft";
                case "RIGHT", "ARROWRIGHT" -> "ArrowRight";
                default -> {
                    if (raw.length() == 1 && raw.charAt(0) >= 32 && raw.charAt(0) <= 126 || raw.matches("F(?:[1-9]|1[0-2])")) yield raw;
                    throw invalid();
                }
            };
            result.add(normalized);
        }
        return result;
    }

    private static void validateShortcut(List<String> keys) {
        if (keys.isEmpty()) throw invalid();
        Set<String> upper = keys.stream().map(k -> k.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        boolean command = upper.contains("CONTROL") || upper.contains("META");
        if (upper.contains("F12") || upper.contains("INSERT") && (command || upper.contains("SHIFT"))
                || command && upper.stream().anyMatch(Set.of("C", "V", "X", "O", "S", "P")::contains)
                || command && upper.contains("SHIFT") && (upper.contains("I") || upper.contains("J"))) {
            throw new ComputerUseException("Host clipboard, files, printing, and developer tools are unavailable in the isolated browser.");
        }
    }

    private static int coordinate(Object value, int limit) {
        int number = integer(value);
        if (number < 0 || number >= limit) throw new ComputerUseException("Computer coordinates must stay inside the 1280 by 800 viewport.");
        return number;
    }
    private static int delta(Object value) { int number = integer(value); if (Math.abs((long) number) > 5000) throw invalid(); return number; }
    private static int integer(Object value) { if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue()) throw invalid(); return n.intValue(); }
    private static String string(Object value) { if (!(value instanceof String text)) throw invalid(); return text; }
    private static ComputerUseException invalid() { return new ComputerUseException("Computer action fields are missing or invalid. Use the structured action schema."); }
}
