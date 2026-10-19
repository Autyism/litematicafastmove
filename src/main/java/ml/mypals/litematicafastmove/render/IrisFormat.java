package ml.mypals.litematicafastmove.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 开着光影包时（1.21.11）让投影的方块网格保持原版方块格式。
 * <p>
 * 1.21.11 的 Iris 在渲染世界期间会把原版方块格式扩展成它自己的地形格式：用这个格式的管线、这期间新建的缓冲都会变。
 * Litematica 在渲染线程上、渲染世界期间构建网格；独立网格却在我们自己的线程上随时构建，格式就要看时机。
 * 所以方块网格一律按原版格式构建，绘制时把 Iris 的“正在渲染世界”标记关掉。
 */
public final class IrisFormat {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

    private IrisFormat() {}

    /** 我们自己的网格构建，格式自己指定为原版方块格式。任何线程。 */
    public static void plainBuild(Runnable build) {
        if (IRIS) {
            IrisFormatHooks.skipExtension(build);
        } else {
            build.run();
        }
    }

    /**
     * Litematica 的网格构建，格式取自原版管线：标记开着时 Iris 给的是扩展后的格式。
     * 只在渲染线程上处理（Litematica 0.26 就在那里构建），别的线程不变。
     */
    public static void plainLitematicaBuild(Runnable build) {
        if (IRIS && RenderSystem.isOnRenderThread()) {
            IrisFormatHooks.plainLevel(() -> IrisFormatHooks.skipExtension(build));
        } else {
            build.run();
        }
    }

    /** 在渲染线程上绘制这些网格。 */
    public static void plainDraw(Runnable draw) {
        if (IRIS) {
            IrisFormatHooks.plainLevel(draw);
        } else {
            draw.run();
        }
    }
}
