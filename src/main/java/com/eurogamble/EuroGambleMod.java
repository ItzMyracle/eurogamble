package com.eurogamble;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalDouble;
import java.util.function.Consumer;

/** EuroGamble: gambling tracker for EuropeMC (client-side). */
public class EuroGambleMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("eurogamble");
    public static GambleConfig config;
    public static KeyBinding openKey;

    private record PendingPay(String command, String player, double amount) {}

    private static PendingPay pendingConfirm;
    private static boolean bypassConfirm;

    private static String lastRaw = "";
    private static long lastRawTime;

    private static int alertTicks;
    private static String alertSession = "";
    private static int lastAlertLoss;

    @Override
    public void onInitializeClient() {
        config = GambleConfig.load();
        Tracker.load();

        // the key can be changed in Options > Controls > Key Binds > EuroGamble
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("eurogamble", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.eurogamble.open", GLFW.GLFW_KEY_B, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new TrackerScreen());
            }
            Tracker.tick();
            if (pendingConfirm != null) {
                PendingPay p = pendingConfirm;
                pendingConfirm = null;
                client.setScreen(new ConfirmPayScreen(p.command(), p.player(), p.amount()));
            }
            checkLossAlert(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Tracker.save());

        // safety: ask before a big /pay really leaves your client
        ClientSendMessageEvents.ALLOW_COMMAND.register(EuroGambleMod::allowCommand);

        // HUD
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("eurogamble", "toast"), new PayToastHud());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("eurogamble", "corner"), new HudOverlays.Corner());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("eurogamble", "hotbar_bar"), new HudOverlays.HotbarBar());

        // chat: payment lines are read here and get clickable buttons appended.
        // Only system messages are used (not player chat), so players cannot fake payments.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : handle(message));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(root("eurogamble"));
            dispatcher.register(root("eg"));
        });
    }

    // ------------------------------------------------------------ commands

    private static LiteralArgumentBuilder<FabricClientCommandSource> root(String name) {
        return ClientCommandManager.literal(name)
            .then(amountCmd("min", v -> config.minAmount = v, "Pop-up minimum"))
            .then(amountCmd("betmin", v -> config.betMin = v, "Minimum bet"))
            .then(amountCmd("betmax", v -> config.betMax = v, "Maximum bet (0 = no limit)"))
            .then(amountCmd("confirm", v -> config.confirmThreshold = v, "Ask before /pay from"))
            .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                config.enabled = !config.enabled;
                config.save();
                ctx.getSource().sendFeedback(Text.literal("Pay pop-ups: " + (config.enabled ? "ON" : "OFF")));
                return 1;
            }))
            .then(ClientCommandManager.literal("sound")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                    config.playSound = BoolArgumentType.getBool(ctx, "on");
                    config.save();
                    ctx.getSource().sendFeedback(Text.literal("Sound: " + (config.playSound ? "ON" : "OFF")));
                    return 1;
                })))
            .then(ClientCommandManager.literal("debug")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                    config.debug = BoolArgumentType.getBool(ctx, "on");
                    config.save();
                    ctx.getSource().sendFeedback(Text.literal("Debug: " + (config.debug ? "ON" : "OFF")));
                    return 1;
                })))
            .then(ClientCommandManager.literal("reload").executes(ctx -> {
                config = GambleConfig.load();
                Tracker.invalidate();
                ctx.getSource().sendFeedback(Text.literal("Config reloaded."));
                return 1;
            }))
            .then(ClientCommandManager.literal("test")
                .executes(ctx -> {
                    notifyPayment(config.minAmount);
                    return 1;
                })
                .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                    .executes(ctx -> {
                        OptionalDouble v = PaymentParser.parseAmount(StringArgumentType.getString(ctx, "amount"));
                        if (v.isEmpty()) {
                            ctx.getSource().sendFeedback(Text.literal("Invalid amount. Try: /eurogamble test 2.5B")
                                .formatted(Formatting.RED));
                            return 0;
                        }
                        notifyPayment(v.getAsDouble());
                        return 1;
                    })))
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal(
                    "EuroGamble - open the tracker with your key (default B)"
                    + "\nPop-ups " + (config.enabled ? "ON" : "OFF") + " from " + AmountFormat.abbreviate(config.minAmount)
                    + " | bets: min " + AmountFormat.abbreviate(config.betMin)
                    + ", max " + (config.betMax <= 0 ? "none" : AmountFormat.abbreviate(config.betMax))
                    + " | rakeback " + config.rakebackPercent + "%"
                    + " | big-pay check " + (config.confirmLargePay ? "ON from " + AmountFormat.abbreviate(config.confirmThreshold) : "OFF")
                    + "\n/eurogamble (or /eg) min | betmin | betmax | confirm <250k, 5M, 1B, 1T> | toggle"
                    + " | sound <true/false> | debug <true/false> | reload | test [amount]"));
                return 1;
            });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> amountCmd(String name, Consumer<Double> setter, String label) {
        return ClientCommandManager.literal(name)
            .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                .executes(ctx -> {
                    OptionalDouble v = PaymentParser.parseAmount(StringArgumentType.getString(ctx, "amount"));
                    if (v.isEmpty()) {
                        ctx.getSource().sendFeedback(Text.literal(
                            "Invalid amount. Examples: 5000, 250k, 1.5M, 2B, 1T").formatted(Formatting.RED));
                        return 0;
                    }
                    setter.accept(v.getAsDouble());
                    config.save();
                    Tracker.invalidate();
                    ctx.getSource().sendFeedback(Text.literal(label + ": " + AmountFormat.abbreviate(v.getAsDouble()))
                        .formatted(Formatting.GREEN));
                    return 1;
                }));
    }

    // ------------------------------------------------- big /pay safety prompt

    private static boolean allowCommand(String command) {
        if (config == null || !config.confirmLargePay || bypassConfirm) return true;
        String payCmd = config.payCommand.startsWith("/") ? config.payCommand.substring(1) : config.payCommand;
        String[] t = command.trim().split("\\s+");
        if (t.length < 3 || !t[0].equalsIgnoreCase(payCmd)) return true;
        OptionalDouble amt = PaymentParser.parseAmount(t[2]);
        if (amt.isEmpty() || amt.getAsDouble() < config.confirmThreshold) return true;
        pendingConfirm = new PendingPay(command.trim(), t[1], amt.getAsDouble());
        return false; // not sent yet; the confirm window opens on the next tick
    }

    /** Called by the confirm window after you clicked "YES, SEND". */
    public static void sendBypassed(String command) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        bypassConfirm = true;
        try {
            mc.getNetworkHandler().sendChatCommand(command);
        } finally {
            bypassConfirm = false;
        }
    }

    // ------------------------------------------------------ losing streak alert

    private static void checkLossAlert(MinecraftClient client) {
        if (!config.lossAlert || !config.trackingEnabled) return;
        if (++alertTicks < 20) return;
        alertTicks = 0;
        Session a = Tracker.active();
        if (a == null) {
            lastAlertLoss = 0;
            alertSession = "";
            return;
        }
        if (!a.id.equals(alertSession)) {
            alertSession = a.id;
            lastAlertLoss = 0;
        }
        int loss = -Tracker.stats(a).curStreak;
        if (loss >= config.lossAlertCount) {
            if (loss != lastAlertLoss) {
                lastAlertLoss = loss;
                PayToastHud.pushText("LOSING STREAK", 0xF87171, "L" + loss + " in a row", 6000);
                if (config.playSound && client.player != null) {
                    client.player.playSound(SoundEvents.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
                }
            }
        } else {
            lastAlertLoss = 0;
        }
    }

    // -------------------------------------------------------------- chat lines

    private static Text handle(Text message) {
        if (config == null) return message;
        String raw = message.getString();

        // ignore exact repeats within 150 ms
        long now = System.currentTimeMillis();
        if (raw.equals(lastRaw) && now - lastRawTime < 150) return message;
        lastRaw = raw;
        lastRawTime = now;

        if (config.debug && looksLikeMoney(raw)) {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("[EuroGamble debug] " + raw.replace('\u00a7', '&')).formatted(Formatting.GRAY), false);
                }
            });
        }

        TxParser.Parsed p = TxParser.parse(raw, config);
        if (p == null) return message;

        String flag = null;
        if (config.trackingEnabled) {
            Tracker.record(p, config);
            flag = Tracker.classify(p, config);
        }
        if (flag != null && config.flagAlerts) {
            notifyFlag(p, flag);
        } else if (p.incoming() && config.enabled && p.amount() >= config.minAmount) {
            notifyPayment(p.amount());
        }
        return ChatButtons.decorate(message, p, config);
    }

    private static boolean looksLikeMoney(String raw) {
        String l = raw.toLowerCase();
        if (config.prefix != null && !config.prefix.isBlank() && l.contains(config.prefix.toLowerCase())) return true;
        return l.contains("paid") || l.contains("sent") || l.contains("received") || l.contains("$");
    }

    private static void notifyFlag(TxParser.Parsed p, String flag) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            String name = p.player().length() > 10 ? p.player().substring(0, 10) : p.player();
            boolean under = flag.equals("UNDER");
            PayToastHud.pushFlag((under ? "UNDER MIN: " : "OVER MAX: ") + name, under ? 0xFB923C : 0xF87171,
                    p.amount(), config.currencySymbol, (long) (config.durationSeconds * 1000));
            if (config.playSound && mc.player != null) {
                mc.player.playSound(SoundEvents.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            }
        });
    }

    private static void notifyPayment(double amount) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            PayToastHud.push(amount, config.currencySymbol, (long) (config.durationSeconds * 1000));
            if (config.playSound && mc.player != null) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (amount >= 1_000_000) mc.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
                if (amount >= 1_000_000_000) mc.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            }
        });
    }
}
