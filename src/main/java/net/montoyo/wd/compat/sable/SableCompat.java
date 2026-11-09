/*
 * Sable compatibility shim, built on the official Sable Companion library
 * (https://github.com/ryanhcode/sable-companion), jar-in-jar'd into this mod.
 *
 * WHY THIS FILE EXISTS:
 * Create Aeronautics' airships/vehicles are NOT Create contraptions under the hood - they are
 * Sable "sub-levels". Per Sable's own docs, a sub-level contains normal Minecraft chunks,
 * entities and block-entities, but those chunks live at a separate, arbitrary ("extreme")
 * position in the plot grid, and get visually rendered at a different, moving/rotating
 * apparent position each frame (its "pose").
 *
 * THE KEY DISCOVERY (made by reading Sable's own source, not just its companion API docs):
 * Sable does NOT need any help positioning our screen quad. Its own rendering pipeline
 * (VanillaSubLevelRenderDispatcher#renderBlockEntities, mirrored by the chunked/"fancy" path)
 * already drives the STANDARD net.minecraft.client.renderer.blockentity.BlockEntityRenderer
 * interface for every block-entity inside a sub-level - including custom ones like
 * ScreenBlockEntity - with a PoseStack that already has the sub-level's current camera-relative
 * transform (position + rotation + scale for this frame's partial tick) baked in via
 * PoseStack#mulPose(transformation), exactly the same pattern Create uses for contraptions
 * (see CreateCompat.java). All that's left to do is translate by the block's own local
 * position, which VanillaSubLevelBlockEntityRenderer#renderSingleBE already does before calling
 * blockEntityRenderDispatcher.render(...).
 *
 * In other words: the vanilla BlockEntityRenderer.render() entry point - WebDisplays'
 * ScreenRenderer - is called correctly for a screen living inside a Sable sub-level, the exact
 * same way it is for a screen inside a Create contraption. The only reason it currently draws
 * nothing is that ScreenRenderer.render() is itself deliberately disabled (it early-returns
 * unless Log.RENDER_DIAG is set) in favor of ScreenStageRenderer's manual camera-chunk scan,
 * which cannot reach a sub-level's chunks since they live far away in the plot grid. So this
 * compat only needs to answer ONE question: "is this block-entity currently inside a Sable
 * sub-level?" - and if so, ScreenRenderer re-enables itself, trusting Sable's own pipeline to
 * supply a correctly transformed PoseStack. No manual chunk scanning, pose-matrix baking, or
 * coordinate-space juggling of our own is required (and, in fact, would risk getting Sable's
 * internal plot-grid/local-space bookkeeping subtly wrong).
 *
 * Sable Companion ships a safe no-op default implementation (DefaultSableCompanion: every
 * lookup simply returns null/empty) that Sable overrides at runtime when actually installed.
 * That means this method is safe to call unconditionally, Sable installed or not - no
 * ModList/reflection guard is needed here (unlike CreateCompat, where Create's own classes are
 * only compileOnly/optional and would throw NoClassDefFoundError if touched without Create
 * present).
 *
 * SECOND BUG FOUND HERE (just as important as the renderer one): WebDisplays decides whether a
 * screen's browser should exist at all - see SharedProxy#distanceTo, used both by
 * ClientProxy#onLevelTick's periodic activate()/deactivate() sweep and by ScreenRenderer's
 * lazy browser creation - by measuring a straight-line distance from the camera to the block
 * entity's raw BlockPos. For a screen inside a Sable sub-level, that raw BlockPos is the LOCAL
 * position within the sub-level's own "plot", which Sable deliberately parks far away from
 * normal play areas (see #isInPlotGrid). So that distance check sees the screen as being
 * thousands of blocks away even while the player is standing right next to the (apparently
 * nearby) airship, meaning onLevelTick's hysteresis logic will keep calling deactivate() -
 * destroying the browser the instant it's created - and activate()/the lazy-creation check will
 * never fire. Without this second fix, a screen on a Sable vehicle would still render as a flat
 * gray/empty quad even after the ScreenRenderer fix above, because no browser would ever be
 * allowed to stay alive. distanceSquared() below fixes this using Sable Companion's own
 * sub-level-aware distance API, which resolves the local plot position back to its true current
 * world-space position before measuring.
 *
 * NOTE: deliberately uses the (Level, Position, double, double, double) overload of
 * distanceSquaredWithSubLevels, NOT the (Level, Position, Position) one - the latter has a
 * copy-paste bug in Sable Companion 1.6.0's own DefaultSableCompanion fallback (its Y and Z
 * terms both evaluate to "b - b" instead of "a - b", silently cancelling to zero), which would
 * make distances come out wrong even on the no-op path used when Sable isn't installed. The
 * double-argument overload doesn't have this bug.
 */
package net.montoyo.wd.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class SableCompat {
    private SableCompat() {
    }

    /**
     * @return true if {@code be} is currently located inside a Sable sub-level on the client
     * (e.g. a Create Aeronautics airship/vehicle body). Always false if Sable isn't installed,
     * since Sable Companion's default no-op implementation always returns null here.
     */
    public static boolean isInSubLevel(BlockEntity be) {
        return SableCompanion.INSTANCE.getContainingClient(be) != null;
    }

    /**
     * Distance-squared from {@code from} (typically the camera/player position) to the block
     * position {@code to}, correctly resolving {@code to} to its true current world-space
     * position first if it happens to sit inside a Sable sub-level's local plot. Falls back to
     * plain Euclidean distance-squared when Sable isn't installed or {@code to} isn't in a
     * sub-level, so this is always safe to use in place of a raw distance check.
     */
    public static double distanceSquared(Level level, Position from, BlockPos to) {
        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, from, to.getX(), to.getY(), to.getZ());
    }

    /**
     * Distance-squared from {@code from} to the exact point {@code (toX, toY, toZ)}, with the
     * same sub-level-aware resolution as {@link #distanceSquared(Level, Position, BlockPos)}.
     */
    public static double distanceSquared(Level level, Position from, double toX, double toY, double toZ) {
        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, from, toX, toY, toZ);
    }

    /**
     * THIRD BUG FOUND HERE: screen click/scroll interaction (the "laser pointer" aim-to-click
     * system in ItemLaserPointer, driven by ClientProxy#raycast) does its own long-range raycast
     * via a plain {@code level.clip(...)} call. Sable hooks {@code BlockGetter#clip} globally (see
     * its {@code clip_overwrite} mixin package) to make that raycast sub-level aware, which is why
     * it correctly identifies a screen block sitting inside a Sable sub-level at all - but it does
     * so using the sub-level's "logical" pose (its authoritative position as of the last game
     * tick), not its "render" pose (the interpolated position actually drawn on screen this frame,
     * via BlockEntityRenderer et al). That's the exact same pose Sable itself swaps in around
     * vanilla's own GameRenderer#pick (see Sable's GameRendererMixin), which is why normal block
     * interaction (mining, placing, "press E") stays pixel-accurate on a moving/rotating vehicle -
     * but our own ad-hoc raycast never gets that swap, since it doesn't run inside GameRenderer's
     * pick() call.
     * <p>
     * For whole-block interaction a one-tick-old pose is a minor, rarely-noticed error. For
     * WebDisplays it is not: on a Create Aeronautics vehicle that's actively flying/turning, one
     * tick's worth of rotation lag, amplified by the screen's distance from the vehicle's pivot,
     * can shift the computed hit point by several pixels of the rendered page - exactly matching
     * clicks landing "on the button next to it" or failing near the edges of a scrolled page.
     * <p>
     * This recomputes just the hit *location* against the already-identified block (its position
     * and face are left untouched - a tick's rotation essentially never changes *which* block or
     * face is being aimed at, only exactly where on it), using the sub-level's current render
     * pose instead. Deliberately does not touch Sable's own clip() override or any of its internal
     * (non-API) pose-provider plumbing - only the public Sable Companion API plus plain vanilla
     * shape-clipping, so this keeps working across Sable versions.
     */
    public static BlockHitResult correctHitForRenderPose(Level level, Vec3 start, Vec3 end, BlockHitResult result) {
        if (result.getType() != HitResult.Type.BLOCK)
            return result;

        ClientSubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(result.getBlockPos());
        if (subLevel == null)
            return result; // not inside a sub-level - the plain world-space hit is already correct

        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        Pose3dc renderPose = subLevel.renderPose(partialTick);

        Vec3 localStart = renderPose.transformPositionInverse(start);
        Vec3 localEnd = renderPose.transformPositionInverse(end);

        BlockPos pos = result.getBlockPos();
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        BlockHitResult localResult = shape.clip(localStart, localEnd, pos);
        if (localResult == null)
            return result; // render-pose-correct ray no longer reaches this block's shape; keep Sable's own result rather than guess

        return new BlockHitResult(localResult.getLocation(), result.getDirection(), pos, result.isInside());
    }

    /**
     * FOURTH BUG FOUND HERE: the screen-cuts-off-at-an-angle bug (reported after the click-position
     * fix above) turned out to be the SAME root cause ScreenStageRenderer's own javadoc already
     * documents for ordinary, ground-mounted screens: NeoForge 1.21's block-entity render dispatch
     * silently skips the renderer call for a block entity at certain camera angles, no matter what
     * {@code getRenderBoundingBox()} returns or whether {@code shouldRenderOffScreen()} is true.
     * ScreenStageRenderer works around this for ground screens by bypassing Minecraft's dispatch
     * entirely and drawing screens itself. A screen inside a Sable sub-level never went through that
     * workaround - Sable's own sub-level dispatcher still drives the standard BlockEntityRenderer
     * interface internally, which inherits the exact same NeoForge bug. This method lets
     * ScreenStageRenderer extend its existing bypass to sub-level screens too: given a block entity,
     * it returns the Sable sub-level containing it (or null if it isn't in one), so the caller can
     * compute that sub-level's current render pose itself and draw the screen with its own code,
     * the same way it already does for ground screens.
     */
    @Nullable
    public static ClientSubLevelAccess getContainingSubLevel(BlockEntity be) {
        return SableCompanion.INSTANCE.getContainingClient(be);
    }
}
