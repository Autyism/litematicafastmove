package ml.mypals.litematicafastmove.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.litematica.config.Configs;
import ml.mypals.exit.api.Exit;
import ml.mypals.exit.api.ExitChannel;
import ml.mypals.exit.api.ExitOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;


public final class SchematicExit {

    public static final boolean ENABLED = !FabricLoader.getInstance().isModLoaded("litematicapass");
    private static ExitChannel channel;
    private static int ownPortals;

    private static ExitChannel channel() {
        if (channel == null) {
            channel = Exit.channel(Identifier.fromNamespaceAndPath("litematicafastmove", "schematic"), ExitOptions.builder()
                    .wantsFrame(Configs.Visuals.ENABLE_RENDERING::getBooleanValue)
                    .build());
        }
        return channel;
    }

    public static void portal(Runnable body) {
        if (ENABLED && (ownPortals > 0 || !Exit.inPortal())) {
            ownPortals++;
            try {
                channel().portal(body);
            } finally {
                ownPortals--;
            }
        } else {
            body.run();
        }
    }

    public static RenderTarget redirect(RenderTarget original) {
        return ENABLED && Exit.isRouting() ? Exit.redirect(original) : original;
    }

    public static SubmitNodeCollector batch(SubmitNodeCollector original) {
        return ENABLED ? channel().batch(original) : original;
    }

    public static void flushBatch() {
        if (ENABLED && (ownPortals > 0 || !Exit.inPortal())) {
            channel().flushBatch();
        }
    }

    public static boolean isActive() {
        return ENABLED && Exit.isActive();
    }

    public static void plainFormat(Runnable build) {
        Exit.plainFormat(build);
    }
}
