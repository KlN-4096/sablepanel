package com.klnon.sablepanel.mixin;

import com.klnon.sablepanel.panel.ops.FreezeService;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 冻结的物理体:plot 内的方块实体整个不 tick。
 * <p>
 * 注入点取自实测崩溃栈(2026-08-08,整组常驻 192 体):
 * {@code ServerLevel.tick → Level.tickBlockEntities:599 → RebindableTickingBlockEntityWrapper.tick:788
 * → BoundTickingBlockEntity.tick:711 → Create FluidNetwork.tick} —— 走的是 vanilla 全局
 * 方块实体循环,不是 sable 的 plot tick,所以只能拦在这里。
 * <p>
 * 这条路径上每个方块实体每 tick 都会过一次,{@link FreezeService#shouldSkipTick} 在没有任何
 * 冻结体时是一次 {@code isEmpty()}。defaultRequire=0,vanilla 改签名时静默失效不崩服。
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void sablepanel$skipFrozen(CallbackInfo ci) {
        BlockEntity be = this.blockEntity;
        if (be == null || be.getLevel() == null) return;
        if (FreezeService.shouldSkipTick(be.getLevel(), be.getBlockPos())) ci.cancel();
    }
}
