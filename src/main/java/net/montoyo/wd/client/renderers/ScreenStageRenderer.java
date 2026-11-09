package net.montoyo.wd.client.renderers;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.block.ScreenThinBlock;
import net.montoyo.wd.client.ClientProxy;
import net.montoyo.wd.compat.sable.SableCompat;
import net.montoyo.wd.config.ClientConfig;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.math.Vector3f;
import net.montoyo.wd.utilities.math.Vector3i;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import static com.mojang.math.Axis.*;

/**
 * Draws WebDisplays screens during {@code RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS},
 * bypassing Minecraft's BlockEntityRenderer iteration entirely.
 *
 * <h2>Why this exists (the screen-blank-at-angles bug fix)</h2>
 *
 * Multiple users reported that WebDisplays screens would visually "blank" at certain camera
 * angles — the gray block-face would show instead of the browser content. The bug existed in
 * the original montoyo mod and persisted through the port. Standard culling fixes (overriding
 * {@code getRenderBoundingBox}, {@code shouldRenderOffScreen=true}, polygon offset, depth-test
 * bypass, custom RenderType, etc.) all failed.
 *
 * <p>The actual cause was discovered via per-frame diagnostic logging on 2026-05-29: Minecraft's
 * BlockEntity render pipeline iterates BEs per chunk SECTION, and only iterates sections that
 * pass Minecraft's internal visibility check. At certain camera angles, the section containing
 * our screen was being marked "not visible", so ALL BEs in that section — including ours — were
 * silently skipped. Our {@link ScreenRenderer#render} was never even called for those frames.
 *
 * <p>{@code shouldRenderOffScreen()=true} is documented to bypass this, but in NeoForge 1.21
 * it does not consistently — the BE's "global vs section-local" classification appears to be
 * cached at chunk-section load time based on {@code getRenderBoundingBox()} at that moment,
 * and changing the AABB or overriding {@code shouldRenderOffScreen} later doesn't always
 * re-classify. The exact internal mechanism wasn't fully isolated, but the workaround is
 * reliable: bypass Minecraft's iteration entirely.
 *
 * <h2>How this fixes it</h2>
 *
 * On every frame at {@code AFTER_TRANSLUCENT_BLOCKS} (during world render, before particles/
 * weather/composition), this handler:
 * <ol>
 *   <li>Iterates loaded chunks in a square around the camera (bounded by render distance)</li>
 *   <li>For each chunk, scans block entities for {@link ScreenBlockEntity} instances</li>
 *   <li>Applies our own frustum check using {@code event.getFrustum()} — only draws screens
 *       that are actually in the camera's view (performance: we still cull invisible screens)</li>
 *   <li>Draws the quad with the MCEF browser texture at the screen's world position</li>
 * </ol>
 *
 * <p>The original {@link ScreenRenderer} is kept registered with the BE dispatcher for two
 * reasons: (1) some Minecraft-internal bookkeeping (texture atlas, BlockEntityType registration)
 * still expects a renderer; (2) it remains useful for diagnostic instrumentation in DEBUG mode.
 * Its actual draw output is redundant with this stage renderer's, so {@link ScreenRenderer#render}
 * early-returns in production mode.
 *
 * <h2>Performance notes</h2>
 *
 * <p>The chunk scan is bounded by Minecraft's render distance (typically 12-24 chunks).
 * For each chunk, we do a constant-time block-entity hashmap iteration and an instanceof
 * filter. The frustum check is a few plane-AABB tests. For a world with N screens, total
 * cost per frame is O(visible_chunks) + O(N) — comparable to Minecraft's own BE iteration.
 *
 * <p>The frustum check ensures we don't waste GL bandwidth drawing screens that aren't in
 * view. Logs show {@code drew=X culled=Y} counts per second when DEBUG is on.
 */
@EventBusSubscriber(modid = "webdisplays", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ScreenStageRenderer {
	private static final Vector3f tmpf = new Vector3f();
	private static final Vector3i tmpi = new Vector3i();
	private static final Vector3f mid = new Vector3f();
	private static long lastLogMs = 0;

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		Camera camera = event.getCamera();
		net.minecraft.world.phys.Vec3 camPos = camera.getPosition();
		Frustum frustum = event.getFrustum();
		PoseStack poseStack = event.getPoseStack();
		Tesselator tesselator = Tesselator.getInstance();
		boolean log = Log.DEBUG && (System.currentTimeMillis() - lastLogMs) >= 1000;
		if (log) lastLogMs = System.currentTimeMillis();

		int drawn = 0;
		int culled = 0;

		// Iterate loaded chunks ourselves, then use OUR OWN frustum check before drawing each screen.
		// This sidesteps NeoForge 1.21's section-based BE iteration culling (which incorrectly skips
		// our renderer at certain camera angles even with shouldRenderOffScreen=true).
		ClientChunkCache chunkSource = level.getChunkSource();
		int camChunkX = ((int) Math.floor(camPos.x)) >> 4;
		int camChunkZ = ((int) Math.floor(camPos.z)) >> 4;
		int viewDist = Math.min(mc.options.renderDistance().get(), 24);

		for (int dx = -viewDist; dx <= viewDist; dx++) {
			for (int dz = -viewDist; dz <= viewDist; dz++) {
				LevelChunk chunk = chunkSource.getChunk(camChunkX + dx, camChunkZ + dz, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!(be instanceof ScreenBlockEntity sbe)) continue;
					if (sbe.isRemoved() || !sbe.isLoaded()) continue;

					// Our own frustum check — inflate by 1 block per axis so edge cases don't cull
					// the screen when its corner is at the frustum boundary.
					net.minecraft.world.phys.AABB box = sbe.getRenderBoundingBox().inflate(1.0);
					if (!frustum.isVisible(box)) { culled++; continue; }

					if (renderScreen(sbe, camPos, poseStack, tesselator)) drawn++;
				}
			}
		}

		// Sub-level screens (Sable sub-levels, e.g. Create Aeronautics vehicles) can never be found
		// by the chunk scan above - their real block data lives far away at Sable's "plot grid"
		// coordinates, well outside render distance of wherever they're currently flying/parked. See
		// renderSubLevelScreens' javadoc for why they need this separate pass instead of just relying
		// on Sable's own block-entity dispatch (which is what draws them today, and the actual cause
		// of the "screen cuts off at an angle" bug this pass fixes).
		drawn += renderSubLevelScreens(poseStack, tesselator, camPos);

		if (log) Log.dbg("stage", "AFTER_TRANSLUCENT_BLOCKS drew=%d culled=%d (chunks within %d radius)", drawn, culled, viewDist);
	}

	/**
	 * Draws every currently-tracked screen that's inside a Sable sub-level, using OUR OWN
	 * transform computed from that sub-level's current render pose - completely bypassing Sable's
	 * own block-entity dispatch, the same way the chunk-scan loop above bypasses Minecraft's.
	 *
	 * <h2>Why this is needed (the screen-cuts-off-at-an-angle bug)</h2>
	 *
	 * After the click-position fix, users still saw screens inside a Sable sub-level visually
	 * "turn off" past a certain camera angle - worse standing to one side of the screen than the
	 * other, and unaffected by an earlier fix that made {@code getRenderBoundingBox()} return
	 * {@code AABB.INFINITE} for these screens (see ScreenBlockEntity#getRenderBoundingBox). That
	 * fix didn't help because it was solving the wrong layer: it assumed the problem was an AABB/
	 * frustum test rejecting the block entity. But this class's own javadoc already documents
	 * that NeoForge 1.21's block-entity dispatch has a *deeper* bug - it silently skips the
	 * renderer call for a block entity at certain camera angles regardless of its AABB or
	 * {@code shouldRenderOffScreen()} - which is exactly why ScreenStageRenderer exists at all for
	 * ordinary ground screens: it was the only reliable fix. A sub-level screen is still drawn
	 * through Sable's own sub-level dispatcher, which internally drives the same standard
	 * BlockEntityRenderer interface and inherits that identical bug. So the same bypass is applied
	 * here, extended to sub-level screens.
	 *
	 * <p>WebDisplays already keeps a live list of every placed screen client-side, in
	 * {@link ClientProxy#getScreens()} (used for the browser activate/deactivate sweep), so this
	 * reuses that instead of scanning for sub-levels from scratch.
	 *
	 * @return the number of screens actually drawn (quad submitted), for the debug log.
	 */
	private static int renderSubLevelScreens(PoseStack poseStack, Tesselator tesselator, net.minecraft.world.phys.Vec3 camPos) {
		if (!(WebDisplays.PROXY instanceof ClientProxy clientProxy)) return 0;

		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		int drawn = 0;

		// Indexed loop: getScreens() backs the same list ClientProxy mutates on block-entity
		// load/unload, and we're reading it mid-frame from the render thread (same thread that
		// mutates it in this single-player-safe client mod, but match the defensive style already
		// used around this list elsewhere in ClientProxy).
		var screens = clientProxy.getScreens();
		for (int i = 0; i < screens.size(); i++) {
			ScreenBlockEntity te = screens.get(i);
			if (te == null || te.isRemoved() || !te.isLoaded()) continue;

			ClientSubLevelAccess subLevel = SableCompat.getContainingSubLevel(te);
			if (subLevel == null) continue; // ground screen - already handled by the chunk scan above

			if (renderSubLevelScreen(te, subLevel, partialTick, camPos, poseStack, tesselator)) drawn++;
		}

		return drawn;
	}

	private static final Vector3d subLevelLocalPoint = new Vector3d();
	private static final Vector3d subLevelWorldPoint = new Vector3d();

	/** Returns true if any quad was drawn for this screen. */
	private static boolean renderSubLevelScreen(ScreenBlockEntity te, ClientSubLevelAccess subLevel, float partialTick,
	                                             net.minecraft.world.phys.Vec3 camPos, PoseStack poseStack, Tesselator tesselator) {
		BlockPos pos = te.getBlockPos();
		Pose3dc renderPose = subLevel.renderPose(partialTick);
		boolean drewAny = false;

		for (int i = 0; i < te.screenCount(); i++) {
			ScreenData scr = te.getScreen(i);
			if (scr == null) continue;

			boolean diag = Log.RENDER_DIAG;
			MCEFBrowser mcefBrowser = null;
			int texId = 0;
			if (!diag) {
				if (scr.browser == null) continue;
				if (!(scr.browser instanceof MCEFBrowser b)) continue;
				if (b.getRenderer() == null || b.getRenderer().getTextureID() == 0) continue;
				mcefBrowser = b;
				texId = b.getRenderer().getTextureID();
			}

			// Same local (block-space) mid offset as renderScreen() - this is a point in the
			// sub-level's own LOCAL/parked block grid, not world space yet.
			tmpi.set(scr.side.right);
			tmpi.mul(scr.size.x);
			tmpi.addMul(scr.side.up, scr.size.y);
			tmpf.set(tmpi);
			mid.set(0.5f, 0.5f, 0.5f);
			mid.addMul(tmpf, 0.5f);
			tmpf.set(scr.side.left);
			mid.addMul(tmpf, 0.5f);
			tmpf.set(scr.side.down);
			mid.addMul(tmpf, 0.5f);

			if (te.getLevel() != null && te.getLevel().getBlockState(pos).getBlock() instanceof ScreenThinBlock) {
				final float thinShift = 15.0f / 16.0f;
				mid.x -= thinShift * scr.side.forward.x;
				mid.y -= thinShift * scr.side.forward.y;
				mid.z -= thinShift * scr.side.forward.z;
			}

			// Resolve the LOCAL anchor point to its true current WORLD/render position via the
			// sub-level's pose, in double precision throughout (see the hit2pixels-caller fixes
			// elsewhere for why narrowing to float before this point would lose precision at
			// Sable's plot-grid coordinates). Only after this do we drop to camera-relative floats,
			// same as renderScreen()'s ground-space version.
			subLevelLocalPoint.set(pos.getX() + (double) mid.x, pos.getY() + (double) mid.y, pos.getZ() + (double) mid.z);
			renderPose.transformPosition(subLevelLocalPoint, subLevelWorldPoint);

			poseStack.pushPose();
			poseStack.translate(
				subLevelWorldPoint.x - camPos.x,
				subLevelWorldPoint.y - camPos.y,
				subLevelWorldPoint.z - camPos.z
			);

			// Apply the sub-level's current orientation BEFORE the existing per-side/per-screen
			// local rotations, so a rotating/flying vehicle correctly carries the screen's face
			// with it. The translation above already baked the orientation into the anchor's
			// position; this is just the remaining rotation for everything drawn relative to it.
			var o = renderPose.orientation();
			poseStack.mulPose(new Quaternionf((float) o.x(), (float) o.y(), (float) o.z(), (float) o.w()));

			switch (scr.side) {
				case BOTTOM: poseStack.mulPose(XP.rotation(90.f + 49.8f)); break;
				case TOP: poseStack.mulPose(XN.rotation(90.f + 49.8f)); break;
				case NORTH: poseStack.mulPose(YN.rotationDegrees(180.f)); break;
				case SOUTH: break;
				case WEST: poseStack.mulPose(YN.rotationDegrees(90.f)); break;
				case EAST: poseStack.mulPose(YP.rotationDegrees(90.f)); break;
			}
			if (!scr.rotation.isNull) poseStack.mulPose(ZP.rotationDegrees(scr.rotation.angle));

			float sw = ((float) scr.size.x) * 0.5f - 2.f / 16.f;
			float sh = ((float) scr.size.y) * 0.5f - 2.f / 16.f;
			if (scr.rotation.isVertical) { float t = sw; sw = sh; sh = t; }

			RenderSystem.enableDepthTest();
			RenderSystem.disableCull();
			if (diag) {
				RenderSystem.setShader(GameRenderer::getPositionColorShader);
				BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
				builder.addVertex(poseStack.last().pose(), -sw, -sh, 0.52f).setColor(0.2f, 0.4f, 1.0f, 1.0f);
				builder.addVertex(poseStack.last().pose(),  sw, -sh, 0.52f).setColor(0.2f, 0.4f, 1.0f, 1.0f);
				builder.addVertex(poseStack.last().pose(),  sw,  sh, 0.52f).setColor(0.2f, 0.4f, 1.0f, 1.0f);
				builder.addVertex(poseStack.last().pose(), -sw,  sh, 0.52f).setColor(0.2f, 0.4f, 1.0f, 1.0f);
				BufferUploader.drawWithShader(builder.buildOrThrow());
			} else {
				RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
				RenderSystem.setShaderTexture(0, texId);
				float brightness = (float) ClientConfig.screenBrightness;
				RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f);
				BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
				builder.addVertex(poseStack.last().pose(), -sw, -sh, 0.52f).setUv(0.f, 1.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(),  sw, -sh, 0.52f).setUv(1.f, 1.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(),  sw,  sh, 0.52f).setUv(1.f, 0.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(), -sw,  sh, 0.52f).setUv(0.f, 0.f).setColor(1.f, 1.f, 1.f, 1.f);
				BufferUploader.drawWithShader(builder.buildOrThrow());
				RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
			}
			RenderSystem.enableCull();
			RenderSystem.disableDepthTest();

			poseStack.popPose();
			drewAny = true;
		}
		return drewAny;
	}

	/** Returns true if any quad was drawn for this screen. */
	private static boolean renderScreen(ScreenBlockEntity te, net.minecraft.world.phys.Vec3 camPos,
	                                     PoseStack poseStack, Tesselator tesselator) {
		BlockPos pos = te.getBlockPos();
		boolean drewAny = false;

		for (int i = 0; i < te.screenCount(); i++) {
			ScreenData scr = te.getScreen(i);
			if (scr == null) continue;

			// In diag mode we draw the green quad regardless of browser state, so user can see
			// the fix is working even before MCEF initializes.
			boolean diag = Log.RENDER_DIAG;
			MCEFBrowser mcefBrowser = null;
			int texId = 0;
			if (!diag) {
				if (scr.browser == null) continue;
				if (!(scr.browser instanceof MCEFBrowser b)) continue;
				if (b.getRenderer() == null || b.getRenderer().getTextureID() == 0) continue;
				mcefBrowser = b;
				texId = b.getRenderer().getTextureID();
			}

			// Compute the screen's mid offset same as ScreenRenderer does.
			tmpi.set(scr.side.right);
			tmpi.mul(scr.size.x);
			tmpi.addMul(scr.side.up, scr.size.y);
			tmpf.set(tmpi);
			mid.set(0.5f, 0.5f, 0.5f);
			mid.addMul(tmpf, 0.5f);
			tmpf.set(scr.side.left);
			mid.addMul(tmpf, 0.5f);
			tmpf.set(scr.side.down);
			mid.addMul(tmpf, 0.5f);

			// THIN BLOCK QUAD OFFSET. ScreenStageRenderer below places the quad at local
			// z=0.52, which is +0.02 past the +Z face of a full 1x1x1 cube — correct for
			// a thick screen block whose front face IS the cell's screen-side face.
			// A thin block's visible slab is on the BACK (mounting side) of the cell, 15/16
			// away from where a thick block's front face would be. Without correction the
			// quad floats ~15/16 of a block behind the slab. We shift `mid` by 15/16 in the
			// OPPOSITE direction of scr.side.forward (= toward the mounting side), so the
			// quad ends up just past the slab's outward face. Owner-block check only — if
			// the multiblock owner is thick, no shift; if thin, shift applies. Mixed
			// multiblocks aren't fully supported (the shift is uniform across the quad).
			if (te.getLevel() != null && te.getLevel().getBlockState(pos).getBlock() instanceof ScreenThinBlock) {
				final float thinShift = 15.0f / 16.0f;
				mid.x -= thinShift * scr.side.forward.x;
				mid.y -= thinShift * scr.side.forward.y;
				mid.z -= thinShift * scr.side.forward.z;
			}

			poseStack.pushPose();
			// Translate from camera-relative origin to BE position + mid offset.
			poseStack.translate(
				pos.getX() - camPos.x + mid.x,
				pos.getY() - camPos.y + mid.y,
				pos.getZ() - camPos.z + mid.z
			);
			switch (scr.side) {
				case BOTTOM: poseStack.mulPose(XP.rotation(90.f + 49.8f)); break;
				case TOP: poseStack.mulPose(XN.rotation(90.f + 49.8f)); break;
				case NORTH: poseStack.mulPose(YN.rotationDegrees(180.f)); break;
				case SOUTH: break;
				case WEST: poseStack.mulPose(YN.rotationDegrees(90.f)); break;
				case EAST: poseStack.mulPose(YP.rotationDegrees(90.f)); break;
			}
			if (!scr.rotation.isNull) poseStack.mulPose(ZP.rotationDegrees(scr.rotation.angle));

			float sw = ((float) scr.size.x) * 0.5f - 2.f / 16.f;
			float sh = ((float) scr.size.y) * 0.5f - 2.f / 16.f;
			if (scr.rotation.isVertical) { float t = sw; sw = sh; sh = t; }

			RenderSystem.enableDepthTest();
			RenderSystem.disableCull();
			if (diag) {
				RenderSystem.setShader(GameRenderer::getPositionColorShader);
				BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
				builder.addVertex(poseStack.last().pose(), -sw, -sh, 0.52f).setColor(0.0f, 1.0f, 0.2f, 1.0f);
				builder.addVertex(poseStack.last().pose(),  sw, -sh, 0.52f).setColor(0.0f, 1.0f, 0.2f, 1.0f);
				builder.addVertex(poseStack.last().pose(),  sw,  sh, 0.52f).setColor(0.0f, 1.0f, 0.2f, 1.0f);
				builder.addVertex(poseStack.last().pose(), -sw,  sh, 0.52f).setColor(0.0f, 1.0f, 0.2f, 1.0f);
				BufferUploader.drawWithShader(builder.buildOrThrow());
			} else {
				RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
				RenderSystem.setShaderTexture(0, texId);
				float brightness = (float) ClientConfig.screenBrightness;
				RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f);
				BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
				builder.addVertex(poseStack.last().pose(), -sw, -sh, 0.52f).setUv(0.f, 1.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(),  sw, -sh, 0.52f).setUv(1.f, 1.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(),  sw,  sh, 0.52f).setUv(1.f, 0.f).setColor(1.f, 1.f, 1.f, 1.f);
				builder.addVertex(poseStack.last().pose(), -sw,  sh, 0.52f).setUv(0.f, 0.f).setColor(1.f, 1.f, 1.f, 1.f);
				BufferUploader.drawWithShader(builder.buildOrThrow());
				RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
			}
			RenderSystem.enableCull();
			RenderSystem.disableDepthTest();

			poseStack.popPose();
			drewAny = true;
		}
		return drewAny;
	}
}
