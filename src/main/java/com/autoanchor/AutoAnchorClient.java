package com.autoanchor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class AutoAnchorClient implements ClientModInitializer {

    // ===== الإعدادات (الوحدة = tick، كل 20 tick = ثانية) =====
    static final double RANGE = 7.0;
    static final int PLACE_DELAY = 2;
    static final int GLOW_SWITCH_DELAY = 1;
    static final int CHARGE_DELAY = 2;
    static final int SAFE_SWITCH_DELAY = 1;
    static final int EXPLODE_DELAY = 3;
    static final int JITTER = 1;
    // =========================================================

    private enum Step { PLACE, SELECT_GLOW, CHARGE, SELECT_SAFE, EXPLODE }

    private record Placement(BlockPos pos, BlockPos against, Direction side) {}

    private KeyBinding toggleKey;
    private boolean enabled = false;
    private Step step = Step.PLACE;
    private int wait = 0;
    private BlockPos anchorPos = null;
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoanchor.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private int delay(int base) {
        return base + (JITTER > 0 ? random.nextInt(JITTER + 1) : 0);
    }

    private void reset() {
        step = Step.PLACE;
        wait = 0;
        anchorPos = null;
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            reset();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("AutoAnchor: " + (enabled ? "ON" : "OFF")), true);
            }
        }

        if (!enabled || mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.currentScreen != null) return;

        PlayerEntity target = findTarget(mc);
        if (target == null) {
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
                Placement p = findPlacement(mc, target);
                if (p == null) return;
                select(mc, slot);
                click(mc, p.against(), p.side());
                anchorPos = p.pos();
                step = Step.SELECT_GLOW;
                wait = delay(PLACE_DELAY);
            }
            case SELECT_GLOW -> {
                if (!anchorExists(mc)) { reset(); return; }
                int slot = findSlot(mc, Items.GLOWSTONE);
                if (slot < 0) return;
                select(mc, slot);
                step = Step.CHARGE;
                wait = delay(GLOW_SWITCH_DELAY);
            }
            case CHARGE -> {
                if (!anchorExists(mc)) { reset(); return; }
                click(mc, anchorPos, Direction.UP);
                step = Step.SELECT_SAFE;
                wait = delay(CHARGE_DELAY);
            }
            case SELECT_SAFE -> {
                if (!anchorExists(mc)) { reset(); return; }
                select(mc, findSafeSlot(mc));
                step = Step.EXPLODE;
                wait = delay(SAFE_SWITCH_DELAY);
            }
            case EXPLODE -> {
                if (anchorExists(mc)) {
                    click(mc, anchorPos, Direction.UP);
                }
                step = Step.PLACE;
                anchorPos = null;
                wait = delay(EXPLODE_DELAY);
            }
        }
    }

    private PlayerEntity findTarget(MinecraftClient mc) {
        PlayerEntity best = null;
        double bestDist = RANGE * RANGE;
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

    private boolean anchorExists(MinecraftClient mc) {
        return anchorPos != null && mc.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private int findSlot(MinecraftClient mc, net.minecraft.item.Item item) {
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

    private void click(MinecraftClient mc, BlockPos clicked, Direction side) {
        Vec3d hitPos = Vec3d.ofCenter(clicked).add(
                side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, side, clicked, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private Placement findPlacement(MinecraftClient mc, PlayerEntity target) {
        BlockPos base = target.getBlockPos();
        BlockPos[] candidates = new BlockPos[] {
                base.up(2),
                base.north(), base.south(), base.east(), base.west(),
                base.north().up(), base.south().up(), base.east().up(), base.west().up()
        };
        Vec3d eyes = mc.player.getEyePos();
        for (BlockPos pos : candidates) {
            if (!mc.world.getBlockState(pos).isReplaceable()) continue;
            if (eyes.squaredDistanceTo(Vec3d.ofCenter(pos)) > 4.5 * 4.5) continue;
            for (Direction d : Direction.values()) {
                BlockPos against = pos.offset(d);
                if (mc.world.getBlockState(against).isSolidBlock(mc.world, against)) {
                    return new Placement(pos, against, d.getOpposite());
                }
            }
        }
        return null;
    }
}
