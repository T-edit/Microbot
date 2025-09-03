package net.runelite.client.plugins.microbot.tempoross;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

public class TemporossStatsOverlay extends OverlayPanel {

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    // Consider moving this to a constants class if reused elsewhere
    private static final String TEMPOROSS_IMG_PATH = "/net/runelite/client/plugins/microbot/tempoross/Tempoross(enraged).png";
    private BufferedImage cachedTemporossImage;

    private final TemporossPlugin plugin;

    @Inject
    public TemporossStatsOverlay(TemporossPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }
        private BufferedImage getTemporossEnragedImage() {
        if (cachedTemporossImage != null) {
            return cachedTemporossImage;
        }
        try {
            BufferedImage img = ImageUtil.loadImageResource(TemporossStatsOverlay.class, TEMPOROSS_IMG_PATH);
            cachedTemporossImage = (img != null) ? ImageUtil.resizeImage(img, 60, 40) : null;
        } catch (Exception e) {
            cachedTemporossImage = null; // Keep null on failure
        }
        return cachedTemporossImage;
    }

    // Format numbers with commas for thousands
    private String formatNumber(int number) {
        return String.format("%,d", number);
    }
    
    // Format duration in milliseconds to HH:MM:SS
    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        // Set up the panel's visual properties
        panelComponent.setPreferredSize(new Dimension(180, 180));
        panelComponent.setBackgroundColor(new Color(173, 216, 230, 40));

        // Render custom title with bold, larger font at top right
        Font originalFont = graphics.getFont();
        graphics.setFont(TITLE_FONT);
        
        String titleText = "TEMPOROSS";
        FontMetrics titleMetrics = graphics.getFontMetrics();
        int titleWidth = titleMetrics.stringWidth(titleText);
        int titleHeight = titleMetrics.getHeight();
        
        // Calculate position at top right of the overlay
        Dimension panelSize = panelComponent.getPreferredSize();
        int titleX = (panelSize != null ? panelSize.width : 180) - titleWidth - 10;
        int titleY = titleHeight;
        
        // Draw shadow for better visibility
        graphics.setColor(Color.RED);
        graphics.drawString(titleText, titleX + 2, titleY + 2);
        
        // Draw the actual title text
        graphics.setColor(Color.CYAN);
        graphics.drawString(titleText, titleX, titleY);
        
        // Restore original font
        graphics.setFont(originalFont);

        // Create and add the image component dynamically
        BufferedImage temporossImage = getTemporossEnragedImage();
        if (temporossImage != null) {
            ImageComponent imageComponent = new ImageComponent(temporossImage);
            panelComponent.getChildren().add(imageComponent);
        }

        // Add plugin status
        if (plugin.started) {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Tempoross Plugin: Enabled")
                    .color(Color.GREEN)
                    .build());
        } else {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Tempoross Plugin: Disabled")
                    .color(Color.RED)
                    .build());
        }

        // Only display statistics when plugin is started
        if (plugin.started) {
            // Add game statistics
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Games:")
                    .right(String.valueOf(plugin.getTotalGames()))
                    .build());

            // Add win/loss statistics
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Wins:")
                    .right(String.valueOf(plugin.getWins()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Losses:")
                    .right(String.valueOf(plugin.getLosses()))
                    .build());
                    
            // Add session reward permits information
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Reward Permits gained:")
                    .right(String.valueOf(plugin.getSessionRewardPermits()))
                    .build());
                    
            // Add reward permits per hour information
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Reward Permit p/Hr:")
                    .right(String.valueOf(plugin.getRewardPermitsPerHour()))
                    .build());
                    
            // Add total reward permits information
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Total Reward Permits:")
                    .right(String.valueOf(plugin.getTotalRewardPermits()))
                    .build());
                    
            // Add fishing XP information
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Fishing XP gained:")
                    .right(formatNumber(plugin.getSessionFishingXp()))
                    .build());
                    
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Fishing XP/Hour:")
                    .right(formatNumber(plugin.getFishingXpPerHour()))
                    .build());
                    
            // Add runtime at the bottom
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(formatTime(plugin.getSessionRuntime()))
                    .build());
        }

        return super.render(graphics);
    }
}