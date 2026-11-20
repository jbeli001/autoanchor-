package com.autoanchor;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class AutoAnchorClient implements ClientModInitializer {

    private static int minDelay = 1;
    private static int maxDelay = 3;

    private KeyBinding toggleKey;
    private boolean enabled = false;
    private int phase = 0; // 0 = فاضي، 1 = ينتظر التفجير، 2 = ينتظر الرجوع للسلوت
    private int wait = 0;
    private int prevSlot = -1;
    private BlockHitResult pendingHit = null;
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoanchor.totem",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC
        ));

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ClientPlayerEntity)) return ActionResult.PASS;
            if (!enabled || phase != 0 || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.GLOWSTONE)) return ActionResult.PASS;
            BlockState state = world.getBlockState(hit.getBlockPos());
            if (!state.isOf(Blocks.RESPAWN_ANCHOR)) return ActionResult.PASS;
            if (state.get(RespawnAnchorBlock.CHARGES) >= 4) return ActionResult.PASS;
            pendingHit = hit;
            prevSlot = player.getInventory().getSelectedSlot();
            phase = 1;
            wait = randomDelay();
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("totemswap")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal(status()));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("toggle")
                                .executes(ctx -> {
                                    enabled = !enabled;
                                    phase = 0;
                                    ctx.getSource().sendFeedback(Text.literal(status()));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("delay")
                                .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(0, 40))
                                        .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(0, 40))
                                                .executes(ctx -> {
                                                    int a = IntegerArgumentType.getInteger(ctx, "min");
                                                    int b = IntegerArgumentType.getInteger(ctx, "max");
                                                    minDelay = Math.min(a, b);
                                                    maxDelay = Math.max(a, b);
                                                    ctx.getSource().sendFeedback(Text.literal(status()));
                                                    return 1;
                                                }))))));
    }

    private String status() {
        return "Totem Swap " + (enabled ? "ON" : "OFF")
                + " | delay=" + minDelay + "-" + maxDelay + " ticks";
    }

    private int randomDelay() {
        return minDelay + random.nextInt(maxDelay - minDelay + 1);
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            phase = 0;
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(status()), true);
            }
        }

        if (phase == 0) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            phase = 0;
            return;
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (phase == 1) {
            if (!mc.world.getBlockState(pendingHit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) {
                phase = 0;
                return;
            }
            int slot = findTotemSlot(mc);
            if (slot < 0) {
                mc.player.sendMessage(Text.literal("Totem Swap: ما في توتم في الـ hotbar"), true);
                phase = 0;
                return;
            }
            mc.player.getInventory().setSelectedSlot(slot);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, pendingHit);
            mc.player.swingHand(Hand.MAIN_HAND);
            phase = 2;
            wait = randomDelay();
        } else if (phase == 2) {
            if (prevSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(prevSlot);
            }
            phase = 0;
        }
    }

    private int findTotemSlot(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }
    }       
