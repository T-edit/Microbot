package net.runelite.client.plugins.microbot.tempoross;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.tempoross.enums.HarpoonType;
import net.runelite.client.plugins.microbot.util.cache.Rs2NpcCache;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

import java.util.regex.Pattern;

@PluginDescriptor(
        name = PluginDescriptor.See1Duck + "Tempoross",
        description = "Tempoross Plugin",
        tags = {"Tempoross", "minigame", "s1d","see1duck","microbot", "fishing","skilling"},
        enabledByDefault = false
)
@Slf4j
public class TemporossPlugin extends Plugin {
    @Inject
    private TemporossConfig config;

    @Inject
    private TemporossOverlay temporossOverlay;

    @Inject
    private TemporossProgressionOverlay temporossProgressionOverlay;

    @Inject
    private TemporossStatsOverlay temporossStatsOverlay;

    @Inject
    private TemporossScript temporossScript;

    @Inject
    private static ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private KeyManager keyManager;

    public boolean started = false;

    private final HotkeyListener pluginToggle = new HotkeyListener(() -> config.startStopHotkey()) {
        @Override
        public void hotkeyPressed() {
            toggle();
        }
    };

    public static int waves = 0;
    public static int fireClouds = 0;
    public static boolean incomingWave = false;
    public static boolean isTethered = false;
    public static long lastWaveHitTime = 0;
    public static State previousState = null;
    public static boolean wasHitByWave = false;

    // Lightning hit handling
    public static boolean lightningHit = false;
    public static long lastLightningHitMs = 0L;

    // Wave recovery grace period to prevent emergency state transitions immediately after wave recovery
    public static long waveRecoveryTime = 0;
    public static final long WAVE_RECOVERY_GRACE_PERIOD_MS = 3000; // 3 seconds grace period

    // Game statistics tracking
    private int totalGames = 0;
    private int wins = 0;
    private int losses = 0;
    private int totalRewardPermits = 0;
    private int previousTotalRewardPermits = 0;
    private int sessionRewardPermits = 0;
    private boolean isFirstPermitVarbitChange = true; // Flag to detect first varbit change after login/startup
    private int sessionBaselinePermits = 0; // Preserve baseline for session calculations during login sequences

    // Plugin startup tracking - to distinguish between initial enable vs restart
    private static boolean isFirstStartup = true;

    // Fishing XP tracking
    private int startingFishingXp = 0;
    private int currentFishingXp = 0;
    private int sessionFishingXp = 0;
    private long sessionStartTime = 0;
    private long pausedTime = 0; // Track total time the plugin was paused
    private long pauseStartTime = 0; // Track when the current pause started

    // Track if player is currently in a game
    private boolean inGame = false;
    // Track if player just won a game (to avoid counting as loss when exiting)
    private boolean justWon = false;

    public void toggle() {
        if (!Microbot.isLoggedIn()) {
            return;
        }

        setStarted(!started);
    }

    public void setStarted(boolean started) {
        boolean wasStarted = this.started;
        this.started = started;

        if (started && !wasStarted) {
            // Starting or resuming
            if (sessionStartTime == 0) {
                // Starting for the first time - initialize session timing
                sessionStartTime = System.currentTimeMillis();
                pausedTime = 0;
                pauseStartTime = 0;
                Microbot.log("Session timing started via hotkey toggle");
            } else {
                // Resuming from pause - add paused time to total
                if (pauseStartTime > 0) {
                    pausedTime += System.currentTimeMillis() - pauseStartTime;
                    pauseStartTime = 0;
                    Microbot.log("Session timing resumed via hotkey toggle");
                }
            }
            Rs2Walker.setTarget(null);
        } else if (!started && wasStarted) {
            // Pausing - record pause start time
            if (sessionStartTime > 0) {
                pauseStartTime = System.currentTimeMillis();
                Microbot.log("Session timing paused via hotkey toggle");
            }
            Rs2Walker.setTarget(null);
        }
    }

    // Getters for game statistics
    public int getTotalGames() {
        return totalGames;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getTotalRewardPermits() {
        return totalRewardPermits;
    }

    public int getSessionRewardPermits() {
        return sessionRewardPermits;
    }

    // Fishing XP getters
    public int getSessionFishingXp() {
        return sessionFishingXp;
    }

    public int getFishingXpPerHour() {
        long timeElapsed = getSessionRuntime();
        // Avoid division by zero
        if (timeElapsed == 0) {
            return 0;
        }

        // Calculate XP per hour
        return (int) (sessionFishingXp * 3600000.0 / timeElapsed);
    }

    public int getRewardPermitsPerHour() {
        long timeElapsed = getSessionRuntime();
        // Avoid division by zero
        if (timeElapsed == 0) {
            return 0;
        }

        // Calculate reward permits per hour
        return (int) (sessionRewardPermits * 3600000.0 / timeElapsed);
    }

    // Get session runtime in milliseconds
    public long getSessionRuntime() {
        if (sessionStartTime == 0) {
            return 0;
        }
        long currentTime = System.currentTimeMillis();
        long totalElapsed = currentTime - sessionStartTime;
        long currentPausedTime = pausedTime;
        
        // If currently paused, add the current pause duration
        if (!started && pauseStartTime > 0) {
            currentPausedTime += currentTime - pauseStartTime;
        }
        
        return totalElapsed - currentPausedTime;
    }

    // Reset all statistics
    public void resetAllStatistics() {
        // Reset session counters
        sessionRewardPermits = 0;
        sessionFishingXp = 0;

        // Reset cumulative game statistics
        totalGames = 0;
        wins = 0;
        losses = 0;

        // Set first permit varbit change flag to handle proper initialization on next varbit event
        isFirstPermitVarbitChange = true;

        // Initialize tracking variables using client thread
        clientThread.invoke(() -> {
            // Get the current permit count from the varbit
            int currentPermits = client.getVarbitValue(TEMPOROSS_REWARDPERMITS);
            previousTotalRewardPermits = currentPermits;
            totalRewardPermits = currentPermits;
            sessionBaselinePermits = currentPermits; // Set baseline for session calculations

            startingFishingXp = client.getSkillExperience(Skill.FISHING);
            currentFishingXp = startingFishingXp;

            Microbot.log("All statistics reset. Current permits: " + totalRewardPermits + ", baseline: " + sessionBaselinePermits + ", isFirstPermitVarbitChange set to true");
        });
        
        // Reset timing variables to completely reset runtime
        sessionStartTime = 0;
        pausedTime = 0;
        pauseStartTime = 0;

        // Reset game state flags
        inGame = false;
        justWon = false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            // Only reset the first permit varbit change flag if this is the very first login after plugin enable
            // This preserves session statistics during automatic plugin restarts (e.g., after login)
            if (isFirstStartup || sessionStartTime == 0) {
                isFirstPermitVarbitChange = true;
                Microbot.log("Player logged in - first login after plugin enable, initializing permit tracking");
            } else {
                Microbot.log("Player logged in - session already active, preserving permit tracking state");
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged statChanged) {
        if (!started) return; // Don't track XP when plugin is paused
        
        if (statChanged.getSkill() == Skill.FISHING) {
            int newXp = statChanged.getXp();
            if (startingFishingXp == 0) {
                // Initialize starting XP if not set
                startingFishingXp = newXp;
                currentFishingXp = newXp;
                // Don't initialize sessionStartTime here - only when plugin is toggled on
            } else if (newXp > currentFishingXp) {
                // Update session XP gained
                sessionFishingXp += (newXp - currentFishingXp);
                currentFishingXp = newXp;
            }
        }
    }

    private static final int VARB_IS_TETHERED = 11895;
    private static final int TEMPOROSS_REWARDPERMITS = 11936;
    private static final int IDLE_RECOVERY_DELAY_MS = 2000; // 2 seconds - reduced from 5 seconds to make recovery faster



    @Provides
    TemporossConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TemporossConfig.class);
    }


    protected void startUp() throws Exception {
        // Always disable the hotkey toggle by default when plugin is enabled
        started = false;
        Microbot.log("Plugin enabled - hotkey toggle disabled by default, user must toggle to start");
        
        // Only reset statistics on the very first startup (when plugin is initially enabled)
        // This preserves session statistics during automatic plugin restarts (e.g., after login)
        if (isFirstStartup) {
            Microbot.log("First startup detected - resetting all statistics");
            resetAllStatistics();
            isFirstStartup = false;
        } else {
            Microbot.log("Plugin restart detected - preserving existing session statistics");
        }

        if (overlayManager != null) {
            toggleOverlay(config.enableOverlay());
            toggleProgressionOverlay(config.showProgressionOverlay());
            toggleStatsOverlay(config.showStatsOverlay());
        }
        EnergyStateManager.init();
        keyManager.registerKeyListener(pluginToggle);
        temporossScript.run(config, this);
    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        EnergyStateManager.shutdown();
        keyManager.unregisterKeyListener(pluginToggle);
        // Reset all statistics when plugin is disabled
        resetAllStatistics();
        temporossScript.shutdown();
        overlayManager.remove(temporossOverlay);
        overlayManager.remove(temporossProgressionOverlay);
        overlayManager.remove(temporossStatsOverlay);
    }

    @Subscribe
    public void onNpcChanged(NpcChanged event) {

        if(!TemporossScript.isInMinigame())
            return;
        if(TemporossScript.workArea == null)
            return;
        TemporossScript.handleWidgetInfo();
        TemporossScript.updateFireData();
        TemporossScript.updateFishSpotData();
        TemporossScript.updateCloudData();
        TemporossScript.updateAmmoCrateData();
    }

    @Subscribe
    public void onGameTick(GameTick e)
    {
        // Check if player has entered or exited the minigame area
        boolean currentlyInMinigame = TemporossScript.isInMinigame();

        // Player entered the minigame area
        if (currentlyInMinigame && !inGame) {
            inGame = true;
            if (started) { // Only count games when plugin is active
                totalGames++;
                Microbot.log("Entered Tempoross minigame area. Total games: " + totalGames);
            }
        }
        // Player exited the minigame area without a win
        else if (!currentlyInMinigame && inGame) {
            // If we haven't registered a win for this game, count it as a loss
            if (!justWon && started) { // Only count losses when plugin is active
                losses++;
                Microbot.log("Left Tempoross minigame area without winning. Total losses: " + losses);
            }
            inGame = false;
            justWon = false;
        }

        if(!currentlyInMinigame)
            return;
        if(TemporossScript.workArea == null)
            return;
        TemporossScript.handleWidgetInfo();
        TemporossScript.updateFireData();
        TemporossScript.updateFishSpotData();
        TemporossScript.updateCloudData();
        TemporossScript.updateAmmoCrateData();
        TemporossScript.updateTotemData();
        TemporossScript.updateMastData();
        TemporossScript.updateLastWalkPath();

        // Check if player was hit by wave and needs to recover
        if (wasHitByWave && lastWaveHitTime > 0) {
            long currentTime = System.currentTimeMillis();
            long idleTime = currentTime - lastWaveHitTime;

            // Check if enough time has passed since the wave hit
            // We don't need to check if player is not animating or moving anymore
            // This ensures the bot will resume its state even if the player is still moving
            Microbot.log("Wave recovery check - Time: " + idleTime + "ms");

            if (idleTime >= IDLE_RECOVERY_DELAY_MS) {

                // Return to previous state if it exists
                if (previousState != null) {
                    Microbot.log("Player recovered from wave hit after " + idleTime + "ms, returning to state: " + previousState);
                    TemporossScript.state = previousState;

                    // Reset wave hit tracking
                    wasHitByWave = false;
                    lastWaveHitTime = 0;
                    incomingWave = false; // Reset incomingWave flag to ensure script continues execution
                    previousState = null;

                    // Set wave recovery grace period to prevent immediate emergency state transitions
                    waveRecoveryTime = System.currentTimeMillis();
                    Microbot.log("Wave recovery grace period activated for " + (WAVE_RECOVERY_GRACE_PERIOD_MS/1000) + " seconds");

                    // Reset all state-specific flags to ensure the bot doesn't get stuck
                    TemporossScript.isFilling = false;
                    TemporossScript.isFightingFire = false;
                    TemporossScript.isRepairingTotem = false;
                    Microbot.log("Reset all state flags after wave recovery");
                } else {
                    // If for some reason previousState is null, set a default state
                    Microbot.log("Previous state was null, setting default state: THIRD_CATCH");
                    TemporossScript.state = State.THIRD_CATCH;

                    // Reset wave hit tracking
                    wasHitByWave = false;
                    lastWaveHitTime = 0;
                    incomingWave = false;

                    // Set wave recovery grace period to prevent immediate emergency state transitions
                    waveRecoveryTime = System.currentTimeMillis();
                    Microbot.log("Wave recovery grace period activated for " + (WAVE_RECOVERY_GRACE_PERIOD_MS/1000) + " seconds (default state)");

                    // Reset all state-specific flags to ensure the bot doesn't get stuck
                    TemporossScript.isFilling = false;
                    TemporossScript.isFightingFire = false;
                    TemporossScript.isRepairingTotem = false;
                    Microbot.log("Reset all state flags after wave recovery (default state)");
                }
            }
        }

        Rs2NpcModel doubleFishingSpot = Rs2NpcCache.getAllNpcs()
                .filter(npc -> npc.getId() == NpcID.TEMPOROSS_HARPOONFISH_FISHINGSPOT_SPECIAL)
                .findFirst()
                .orElse(null);

        if (TemporossScript.state == State.INITIAL_COOK && doubleFishingSpot != null) {
            TemporossScript.state = TemporossScript.state.next;
        }

        if (TemporossScript.INTENSITY >= 94 && TemporossScript.state == State.THIRD_COOK)
        {
            return;
        }

        // Handle emergency catch state transitions during game ticks
        EnergyStateManager.handleEmergencyCatchStateTransition();

        // If state is null and we're not in wave hit recovery mode, set a default state
        if (TemporossScript.state == null && !wasHitByWave)
        {
            TemporossScript.state = State.THIRD_CATCH;
            Microbot.log("State was null, setting default state: " + TemporossScript.state);
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (event.getVarbitId() == VARB_IS_TETHERED)
        {
            Microbot.log("Tethered: " + event.getValue());
            isTethered = event.getValue() > 0;
        }
        else if (event.getVarbitId() == TEMPOROSS_REWARDPERMITS)
        {
            int currentPermits = event.getValue();

            // Always log the varbit change for debugging
            Microbot.log("PERMIT VARBIT CHANGED: previousTotal=" + previousTotalRewardPermits +
                         ", currentTotal=" + currentPermits +
                         ", storedTotal=" + totalRewardPermits +
                         ", isFirst=" + isFirstPermitVarbitChange);

            // If this is the first varbit change (login/startup), initialize tracking variables
            if (isFirstPermitVarbitChange) {
                // Initialize with current permits, but also check if we should count this as a gain
                if (previousTotalRewardPermits == 0 && totalRewardPermits == 0) {
                    // True initialization - set baseline
                    previousTotalRewardPermits = currentPermits;
                    totalRewardPermits = currentPermits;
                    sessionBaselinePermits = currentPermits; //Set session baseline for proper session calculations
                    Microbot.log("Initialized permit tracking baseline: " + totalRewardPermits + ", session baseline: " + sessionBaselinePermits);
                } else {
                    // Plugin restart - we may have gained permits, so count the difference
                    if (currentPermits > totalRewardPermits && started) { // Only count gains when plugin is active
                        int gained = currentPermits - totalRewardPermits;
                        sessionRewardPermits += gained;
                        Microbot.log("Plugin restart detected - counted " + gained + " permits gained. Session total: " + sessionRewardPermits);
                    }
                    previousTotalRewardPermits = currentPermits;
                    totalRewardPermits = currentPermits;
                    // Set session baseline
                    if (sessionBaselinePermits == 0) {
                        sessionBaselinePermits = currentPermits;
                        Microbot.log("Set session baseline during restart: " + sessionBaselinePermits);
                    }
                    Microbot.log("Reinitialized permit tracking after restart: " + totalRewardPermits);
                }
                isFirstPermitVarbitChange = false;
                return;
            }

            // Only track permit changes when plugin is active
            if (!started) {
                // Still update tracking variables but don't count gains
                previousTotalRewardPermits = currentPermits;
                totalRewardPermits = currentPermits;
                return;
            }

            // If total permits changed, update tracking variables
            if (currentPermits != totalRewardPermits) {
                // If permits increased, update session permits
                if (currentPermits > previousTotalRewardPermits) {
                    // FIXED RECOVERY LOGIC: Check if we're recovering from login varbit fluctuation
                    // Detect recovery pattern: previousTotal=0 AND currentPermits is close to our session baseline
                    if (previousTotalRewardPermits == 0 && Math.abs(currentPermits - sessionBaselinePermits) <= sessionRewardPermits) {
                        // This is a login varbit recovery - don't count as gain
                        Microbot.log("Login varbit recovery detected: 0 → " + currentPermits + " (baseline: " + sessionBaselinePermits + ", session: " + sessionRewardPermits + ") - no gain counted");
                    } else {
                        // Calculate actual gains - only count increases beyond session baseline + current session gains
                        int expectedTotal = sessionBaselinePermits + sessionRewardPermits;
                        if (currentPermits > expectedTotal) {
                            int gained = currentPermits - expectedTotal;
                            sessionRewardPermits += gained;
                            Microbot.log("Gained " + gained + " reward permits. Session total: " + sessionRewardPermits);
                        } else {
                            Microbot.log("Permit value increased but not beyond expected total (no new gains)");
                        }
                    }
                } else if (currentPermits < previousTotalRewardPermits) {
                    // Log when permits decrease (this shouldn't normally happen)
                    Microbot.log("WARNING: Permit count decreased from " + previousTotalRewardPermits + " to " + currentPermits);
                }

                // Always update the tracking variables regardless of whether permits increased or decreased
                previousTotalRewardPermits = currentPermits;
                totalRewardPermits = currentPermits;
                Microbot.log("Total reward permits updated: " + totalRewardPermits);
            } else {
                // Log when the varbit changes but the value is the same as our stored value
                Microbot.log("Permit varbit event received but value unchanged: " + currentPermits);
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        String message = event.getMessage();

        if (type == ChatMessageType.GAMEMESSAGE)
        {
            if (message.contains("A colossal wave closes in"))
            {
                waves++;
                incomingWave = true;
                Microbot.log("Wave " + waves);
            }

            if (message.contains("the rope keeps you securely") || message.contains("the wave slams into you"))
            {
                incomingWave = false;
                Microbot.log("Wave passed");

                // If hit by wave, save current time and state for recovery
                if (message.contains("the wave slams into you")) {
                    lastWaveHitTime = System.currentTimeMillis();
                    previousState = TemporossScript.state;
                    wasHitByWave = true;
                    // Set isTethered to false since the player is no longer tethered after being hit by a wave
                    isTethered = false;
                    Microbot.log("Wave hit player, saving state: " + previousState + " (will resume in " + (IDLE_RECOVERY_DELAY_MS/1000) + " seconds)");
                    // Force the player to idle state temporarily
                    TemporossScript.state = null;
                }
            }
            if (message.contains("A strong wind blows as clouds roll in"))
            {
                fireClouds++;
                Microbot.log("Clouds " + fireClouds);
            }

            // Lightning stun from cannon
            if (message.contains("The surge of lightning arcs from the cannon into you"))
            {
                lightningHit = true;
                lastLightningHitMs = System.currentTimeMillis();
                Microbot.log("Lightning hit detected - will switch ammo crate after stun");
            }

            // Track game completions
            if (message.contains("Reward permits:"))
            {
                // Track wins only when plugin is active
                if (started) {
                    wins++;
                    Microbot.log("Tempoross defeated! Total wins: " + wins);
                    Microbot.log("Total games played: " + totalGames);
                }
                // Mark that player just won a game (to avoid counting as loss when exiting)
                justWon = true;
            }
        }
    }

    // Set harpoon type config
    public static void setHarpoonType(HarpoonType harpoonType) {
        Microbot.getConfigManager().setConfiguration("microbot-tempoross", "harpoonType", harpoonType);
    }

    // Set rope config
    public static void setRope(boolean rope) {
        Microbot.getConfigManager().setConfiguration("microbot-tempoross", "rope", rope);
    }

    // Toggle overlay visibility
    public void toggleOverlay(boolean show) {
        if (show) {
            overlayManager.add(temporossOverlay);
        } else {
            overlayManager.remove(temporossOverlay);
        }
    }

    // Toggle progression overlay visibility
    public void toggleProgressionOverlay(boolean show) {
        if (show) {
            overlayManager.add(temporossProgressionOverlay);
        } else {
            overlayManager.remove(temporossProgressionOverlay);
        }
    }

    // Toggle stats overlay visibility
    public void toggleStatsOverlay(boolean show) {
        if (show) {
            overlayManager.add(temporossStatsOverlay);
        } else {
            overlayManager.remove(temporossStatsOverlay);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals("microbot-tempoross")) {
            if (event.getKey().equals("enableOverlay")) {
                boolean enableOverlay = Boolean.parseBoolean(event.getNewValue());
                toggleOverlay(enableOverlay);
            } else if (event.getKey().equals("showProgressionOverlay")) {
                boolean showProgressionOverlay = Boolean.parseBoolean(event.getNewValue());
                toggleProgressionOverlay(showProgressionOverlay);
            } else if (event.getKey().equals("showStatsOverlay")) {
                boolean showStatsOverlay = Boolean.parseBoolean(event.getNewValue());
                toggleStatsOverlay(showStatsOverlay);
            }
        }
    }
}