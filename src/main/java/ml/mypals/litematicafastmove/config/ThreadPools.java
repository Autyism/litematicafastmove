package ml.mypals.litematicafastmove.config;

import java.util.concurrent.ThreadPoolExecutor;

public final class ThreadPools {
    private ThreadPools() {}

    /**
     * 运行时调整固定大小线程池的线程数；多余的线程在空闲后退出，进行中的任务不受影响
     */
    public static void resize(ThreadPoolExecutor pool, int threads) {
        int size = Math.max(1, threads);
        if (size > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(size);
            pool.setCorePoolSize(size);
        } else {
            pool.setCorePoolSize(size);
            pool.setMaximumPoolSize(size);
        }
    }
}
