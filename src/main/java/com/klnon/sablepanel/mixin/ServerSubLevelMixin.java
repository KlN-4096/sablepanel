package com.klnon.sablepanel.mixin;

import com.klnon.sablepanel.panel.metrics.BodyCostTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 逐体计时:tick()(主线程逻辑)与 prePhysicsTick()(力应用,可能在物理线程)。
 * 两方法各用独立的 t0 字段,互不串扰;defaultRequire=0,sable 改签名时静默失效不崩服。
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin {
    @Unique
    private long sablepanel$tickT0;
    @Unique
    private long sablepanel$physT0;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void sablepanel$tickHead(CallbackInfo ci) {
        this.sablepanel$tickT0 = BodyCostTracker.ENABLED ? System.nanoTime() : 0;
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void sablepanel$tickReturn(CallbackInfo ci) {
        if (!BodyCostTracker.ENABLED || this.sablepanel$tickT0 == 0) return;
        try {
            BodyCostTracker.add(((ServerSubLevel) (Object) this).getUniqueId(),
                    System.nanoTime() - this.sablepanel$tickT0);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "prePhysicsTick", at = @At("HEAD"))
    private void sablepanel$physHead(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system,
                                     dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle handle,
                                     double timeStep, CallbackInfo ci) {
        this.sablepanel$physT0 = BodyCostTracker.ENABLED ? System.nanoTime() : 0;
    }

    @Inject(method = "prePhysicsTick", at = @At("RETURN"))
    private void sablepanel$physReturn(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system,
                                       dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle handle,
                                       double timeStep, CallbackInfo ci) {
        if (!BodyCostTracker.ENABLED || this.sablepanel$physT0 == 0) return;
        try {
            BodyCostTracker.add(((ServerSubLevel) (Object) this).getUniqueId(),
                    System.nanoTime() - this.sablepanel$physT0);
        } catch (Throwable ignored) {
        }
    }
}
