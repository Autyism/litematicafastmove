package ml.mypals.litematicafastmove.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vertices.ImmediateState;

/** {@link IrisFormat} 里调用 Iris 的部分，只在装了 Iris 时加载 */
final class IrisFormatHooks {
    private IrisFormatHooks() {}

    // ImmediateState.skipExtension 按线程生效：本线程新建的缓冲保持原来的格式
    static void skipExtension(Runnable body) {
        boolean previous = ImmediateState.skipExtension.get();
        ImmediateState.skipExtension.set(true);
        try {
            body.run();
        } finally {
            ImmediateState.skipExtension.set(previous);
        }
    }

    // 只有这个标记开着时 Iris 才给扩展格式；只在渲染线程上改
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
