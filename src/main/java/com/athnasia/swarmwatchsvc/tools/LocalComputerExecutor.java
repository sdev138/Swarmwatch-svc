package com.athnasia.swarmwatchsvc.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;

final class LocalComputerExecutor implements ComputerExecutor {
    public Result execute(String tenantId, String agentId, Map<String, Object> computerCall) {
        ComputerAction.batch(computerCall);
        try {
            BufferedImage image = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(new Color(237, 242, 248)); graphics.fillRect(0, 0, 1280, 800);
            graphics.setColor(new Color(23, 43, 70)); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            graphics.drawString("SIMULATED COMPUTER — LOCAL MODE", 90, 160);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            graphics.drawString("No browser actions or network requests were performed.", 90, 220);
            graphics.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
            return new Result("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()), "local-simulated://computer");
        } catch (Exception failure) { throw new ComputerUseException("The local simulated computer screenshot is unavailable."); }
    }
    public void release(String tenantId, String agentId) { }
    public void close() { }
}
