package com.autoanchor;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

public class AutoAnchorClient implements ClientModInitializer {

    // ===== الإعدادات (تتغير بالأوامر داخل اللعبة) =====
    private static double range = 7.0;   // المسافة من العدو بالبلوكات
    private static int minDelay = 1;     // أقل tick بين الخطوات
    private static int maxDelay = 4;     // أكثر tick بين الخطوات
    // ==================================================

    private static final int MAX_STUCK = 40;

    private enum Step { PLACE, SELECT_GLOW, CHARGE, SELECT_SAFE, EXPLODE }

    private KeyBinding toggleKey;
    private boolean enabled = false;
    private Step step = Step.PLACE;
    private int wait = 0;
    private int stuck = 0;
    private BlockPos anchorPos = null;
    private final Random random = new Random();
    private Path configPath;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("autoanchor.properties");
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoanchor.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autoanchor")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal(status()));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("toggle")
                                .executes(ctx -> {
                                    enabled = !enabled;
                                    reset();
                                    ctx.getSource().sendFeedback(Text.literal(status()));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("range")
                                .then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(1.0, 20.0))
                                        .executes(ctx -> {
                                            range = DoubleArgumentType.getDouble(ctx, "blocks");
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Text.literal(status()));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("delay")
                                .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(0, 40))
                                        .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(0, 40))
                                                .executes(ctx -> {
                                                    int a = IntegerArgumentType.getInteger(ctx, "min");
                                                    int b = IntegerArgumentType.getInteger(ctx, "max");
                                                    minDelay = Math.min(a, b);
                                                    maxDelay = Math.max(a, b);
                                                    saveConfig();
                                                    ctx.getSource().sendFeedback(Text.literal(status()));
                                                    return 1;
                                                }))))));
    }

    private String status() {
        return "AutoAnchor " + (enabled ? "ON" : "OFF")
                + " | range=" + range + " blocks"
                + " | delay=" + minDelay + "-" + maxDelay + " ticks";
    }

    private void loadConfig() {
        if (!Files.exists(configPath)) return;
        try (var in = Files.newInputStream(configPath)) {
            Properties p = new Properties();
            p.load(in);
            range = Double.parseDouble(p.getProperty("range", "7"));
            minDelay = Integer.parseInt(p.getProperty("minDelay", "1"));
            maxDelay = Integer.parseInt(p.getProperty("maxDelay", "4"));
        } catch (Exception ignored) {
        }
    }

    private void saveConfig() {
        try (var out = Files.newOutputStream(configPath)) {
            Properties p = new Properties();
            p.setProperty("range", String.valueOf(range));
            p.setProperty("minDelay", String.valueOf(minDelay));
            p.setProperty("maxDelay", String.valueOf(maxDelay));
            p.store(out, "AutoAnchor");
        } catch (IOException ignored) {
        }
    }

    private int randomDelay() {
        int lo = Math.min(minDelay, maxDelay);
        int hi = Math.max(minDelay, maxDelay);
        return lo + random.nextInt(hi - lo + 1);
    }

    private void reset() {
        step = Step.PLACE;
        wait = 0;
        stuck = 0;
        anchorPos = null;
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            reset();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(status()), true);
            }
        }

        if (!enabled || mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.currentScreen != null) return;

        if (findTarget(mc) == null) {
            reset();
            return;
        }

        if (wait > 0) {
            wait--;
            return;
        }

        switch (step) {
            case PLACE -> {
                int slot = findSlot(mc, Items.RESPAWN_ANCHOR);
                if (slot < 0) return;
                BlockHitResult aim = aimedBlock(mc);
                if (aim == null) return;
                BlockPos clicked = aim.getBlockPos();
                if (mc.world.getBlockState(clicked).isOf(Blocks.RESPAWN_ANCHOR)) return;
                BlockPos placeAt = mc.world.getBlockState(clicked).isReplaceable()
                        ? clicked : clicked.offset(aim.getSide());
                select(mc, slot);
                interact(mc, aim);
                anchorPos = placeAt;
                step = Step.SELECT_GLOW;
                stuck = 0;
                wait = randomDelay();
            }
            case SELECT_GLOW -> {
                if (!anchorExists(mc)) { reset(); return; }
                int slot = findSlot(mc, Items.GLOWSTONE);
                if (slot < 0) return;
                select(mc, slot);
                step = Step.CHARGE;
                wait = randomDelay();
            }
            case CHARGE -> {
                if (!anchorExists(mc)) { reset(); return; }
                BlockHitResult aim = aimedAnchor(mc);
                if (aim == null) { if (++stuck > MAX_STUCK) reset(); return; }
                interact(mc, aim);
                stuck = 0;
                step = Step.SELECT_SAFE;
                wait = randomDelay();
            }
            case SELECT_SAFE -> {
                if (!anchorExists(mc)) { reset(); return; }
                select(mc, findSafeSlot(mc));
                step = Step.EXPLODE;
                wait = randomDelay();
            }
            case EXPLODE -> {
                if (!anchorExists(mc)) { reset(); return; }
                BlockHitResult aim = aimedAnchor(mc);
                if (aim == null) { if (++stuck > MAX_STUCK) reset(); return; }
                interact(mc, aim);
                step = Step.PLACE;
                stuck = 0;
                anchorPos = null;
                wait = randomDelay();
            }
        }
    }

    private PlayerEntity findTarget(MinecraftClient mc) {
        PlayerEntity best = null;
        double bestDist = range * range;
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || other.isSpectator() || !other.isAlive()) continue;
            double d = mc.player.squaredDistanceTo(other);
            if (d <= bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    private BlockHitResult aimedBlock(MinecraftClient mc) {
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private BlockHitResult aimedAnchor(MinecraftClient mc) {
        BlockHitResult hit = aimedBlock(mc);
        if (hit != null && hit.getBlockPos().equals(anchorPos)) return hit;
        return null;
    }

    private boolean anchorExists(MinecraftClient mc) {
        return anchorPos != null && mc.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private int findSlot(MinecraftClient mc, Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private int findSafeSlot(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            var s = mc.player.getInventory().getStack(i);
            if (!s.isOf(Items.GLOWSTONE) && !s.isOf(Items.RESPAWN_ANCHOR)) return i;
        }
        return mc.player.getInventory().getSelectedSlot();
    }

    private void select(MinecraftClient mc, int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private void interact(MinecraftClient mc, BlockHitResult hit) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
                                                         }
