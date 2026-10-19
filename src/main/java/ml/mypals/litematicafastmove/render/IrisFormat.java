package ml.mypals.litematicafastmove.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Keeps the schematic's block meshes in the vanilla block format while a shader pack is in use (1.21.11).
 * <p>
 * Iris for 1.21.11 widens the block format into its terrain format whenever the level is being rendered: the format
 * of pipelines that use it, and the buffers built for it. Litematica builds its meshes on the render thread while the
 * level renders, but the detached meshes are built on our own threads, at any time, so their format would depend on
 * timing. Instead every block mesh is built in the vanilla format and drawn with the level flag off.
 */
public final class IrisFormat {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

    private IrisFormat() {}

    /** Our own mesh builds, which name the vanilla block format themselves. Any thread. */
    public static void plainBuild(Runnable build) {
        if (IRIS) {
            IrisFormatHooks.skipExtension(build);
        } else {
            build.run();
        }
    }

    /**
     * Litematica's mesh builds, which take the format from the game's pipelines: Iris hands those out widened while
     * the level flag is on. On the render thread only, where Litematica 0.26 builds; anywhere else nothing changes.
     */
    public static void plainLitematicaBuild(Runnable build) {
        if (IRIS && RenderSystem.isOnRenderThread()) {
            IrisFormatHooks.plainLevel(() -> IrisFormatHooks.skipExtension(build));
        } else {
            build.run();
        }
    }

    /** Drawings of those meshes, on the render thread. */
    public static void plainDraw(Runnable draw) {
        if (IRIS) {
            IrisFormatHooks.plainLevel(draw);
        } else {
            draw.run();
        }
    }
}
