package ml.mypals.litematicafastmove.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vertices.ImmediateState;

/** The Iris side of {@link IrisFormat}, only loaded when Iris is. */
final class IrisFormatHooks {
    private IrisFormatHooks() {}

    // ImmediateState.skipExtension is per thread: buffers built on this thread keep their format
    static void skipExtension(Runnable body) {
        boolean previous = ImmediateState.skipExtension.get();
        ImmediateState.skipExtension.set(true);
        try {
            body.run();
        } finally {
            ImmediateState.skipExtension.set(previous);
        }
    }

    // Iris only hands out its widened formats while this is on; render thread only
    static void plainLevel(Runnable body) {
        if (!Iris.isPackInUseQuick()) {
            body.run();
            return;
        }
        boolean previous = ImmediateState.isRenderingLevel;
        ImmediateState.isRenderingLevel = false;
        try {
            body.run();
        } finally {
            ImmediateState.isRenderingLevel = previous;
        }
    }
}
