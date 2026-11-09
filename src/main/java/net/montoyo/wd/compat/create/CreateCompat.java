/*
 * Create-mod compatibility shim.
 *
 * WHY THIS FILE EXISTS:
 * WebDisplays' real screen content (the Chromium texture) is drawn by ScreenStageRenderer,
 * which bypasses Minecraft's BlockEntityRenderer iteration entirely and instead walks the
 * REAL ClientLevel's loaded chunks every frame (see ScreenStageRenderer's javadoc for why).
 *
 * That works fine for screens placed normally in the world, but it means a screen can NEVER
 * be found once it becomes part of an assembled Create contraption (airship, train, etc.):
 * Create moves the block entity into its own VirtualRenderWorld, which is not one of the
 * real level's loaded chunks, so ScreenStageRenderer's chunk scan will never see it.
 *
 * Create DOES, however, still call the block's normal BlockEntityRenderer for contraption-
 * mounted block entities (see com.simibubi.create.foundation.render.BlockEntityRenderHelper),
 * with a PoseStack that already has the contraption's full transform applied (position +
 * rotation, however that rotation is computed - including by physics addons such as
 * Create Aeronautics/Sable, since that all happens upstream in the contraption entity itself).
 * ScreenRenderer (WebDisplays' normal BlockEntityRenderer) already contains fully correct
 * drawing code for this - it's simply dead code in production, disabled by a blanket
 * "if (!Log.RENDER_DIAG) return;" that exists only to avoid double-drawing in the normal
 * (non-contraption) world case, which ScreenStageRenderer already handles.
 *
 * So: ScreenRenderer only needs to know "is this call happening because I'm inside a
 * Create contraption?" - if yes, it should go ahead and draw instead of early-returning.
 *
 * Every direct reference to a Create class lives ONLY in this file, and this file is only
 * ever touched from ScreenRenderer after it has confirmed (via ModList) that Create is
 * actually installed. This keeps WebDisplays working completely normally - including in
 * modpacks that don't have Create at all - since the JVM never has to resolve/verify this
 * class unless Create is present.
 */
package net.montoyo.wd.compat.create;

import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.world.level.Level;

public final class CreateCompat {
    private CreateCompat() {
    }

    /**
     * @return true if {@code level} is one of Create's per-contraption "virtual" render
     * levels - i.e. we're being asked to render a block entity that lives inside an
     * assembled contraption rather than in the real world.
     */
    public static boolean isVirtualRenderWorld(Level level) {
        return level instanceof VirtualRenderWorld;
    }
}
