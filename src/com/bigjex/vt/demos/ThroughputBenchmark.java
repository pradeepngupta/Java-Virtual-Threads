package com.bigjex.vt.demos;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

/**
 * Demo 1: Throughput Benchmark — Platform vs Virtual Threads
 * 
 * This demo simulates 10,000 concurrent HTTP-like tasks (each blocking ~100ms on a simulated I/O call).
 * 
 * Execution:
 *   1. Run with platform threads (ThreadPoolExecutor with 200 threads)
 *   2. Observe wall-clock time and thread count (watch via JConsole or VisualVM)
 *   3. Comment out the platform threads section
 *   4. Uncomment the virtual threads section
 *   5. Run again and observe the dramatic speedup
 * 
 * Expected results:
 *   - Platform threads (200-thread pool): ~4-6 seconds for 10,000 tasks
 *   - Virtual threads: ~1-2 seconds for 10,000 tasks (10-50x faster)
 *   - Platform thread count: peaks at ~200
 *   - Virtual thread count: can reach millions on same hardware
 */
public class ThroughputBenchmark {

    static class Task implements Runnable {
        private final int taskId;
        private final SimulatedIO io;
        private final AtomicInteger completedTasks;

        Task(int taskId, SimulatedIO io, AtomicInteger completedTasks) {
            this.taskId = taskId;
            this.io = io;
            this.completedTasks = completedTasks;
        }

        @Override
        public void run() {
            try {
                // Simulate an HTTP request that blocks on I/O (DB query, network call, file read)
                // In reality, this is when the OS thread would sit idle and waste CPU
                io.blockingCall(100); // Block for 100ms

                // Task complete
                completedTasks.incrementAndGet();

                // Print progress every 1000 tasks
                if (taskId % 1000 == 0) {
                    System.out.println(
                        String.format(
                            "[Task %d complete] Completed: %d  |  Active threads: %d  |  Thread name: %s",
                            taskId,
                            completedTasks.get(),
                            Thread.activeCount(),
                            Thread.currentThread().getName()
                        )
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Task " + taskId + " interrupted");
            }
        }
    }

    static class SimulatedIO {
        void blockingCall(long milliseconds) throws InterruptedException {
            Thread.sleep(milliseconds);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=".repeat(80));
        System.out.println("Demo 1: Platform vs Virtual Threads Throughput Benchmark");
        System.out.println("=".repeat(80));
        System.out.println("Simulating 10,000 concurrent I/O-bound tasks (each blocking ~100ms)\n");

        int taskCount = 10_000;
        SimulatedIO io = new SimulatedIO();

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // BENCHMARK 1: Platform Threads (ThreadPoolExecutor with fixed thread pool)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n📊 BENCHMARK 1: Platform Threads (FixedThreadPool with 200 threads)");
        System.out.println("-".repeat(80));

        AtomicInteger platformCompleted;
        long platformStartTime;
        try (ExecutorService platformExecutor = newFixedThreadPool(200)) {
            platformCompleted = new AtomicInteger(0);

            platformStartTime = System.currentTimeMillis();
            System.out.println("Starting " + taskCount + " tasks...");

            for (int i = 0; i < taskCount; i++) {
                platformExecutor.submit(new Task(i, io, platformCompleted));
            }

            // Wait for all tasks to complete
            platformExecutor.shutdown();
            boolean platformFinished = platformExecutor.awaitTermination(30, TimeUnit.SECONDS);
            System.out.println("All platform tasks finished: " + platformFinished);
        }
        long platformEndTime = System.currentTimeMillis();
        long platformDuration = platformEndTime - platformStartTime;

        System.out.println("\n✓ Platform Threads Benchmark Complete:");
        System.out.println(String.format("  Tasks completed: %d", platformCompleted.get()));
        System.out.println(String.format("  Wall-clock time: %.2f seconds", platformDuration / 1000.0));
        System.out.println(String.format("  Peak thread count: ~200 (fixed pool size)"));
        System.out.println(String.format("  Memory usage: ~200 MB (1 MB per OS thread)"));

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // BENCHMARK 2: Virtual Threads (newVirtualThreadPerTaskExecutor)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n📊 BENCHMARK 2: Virtual Threads (newVirtualThreadPerTaskExecutor)");
        System.out.println("-".repeat(80));

        AtomicInteger virtualCompleted;
        long virtualStartTime;
        try (ExecutorService virtualExecutor = newVirtualThreadPerTaskExecutor()) {
            virtualCompleted = new AtomicInteger(0);

            virtualStartTime = System.currentTimeMillis();
            System.out.println("Starting " + taskCount + " tasks...");

            for (int i = 0; i < taskCount; i++) {
                virtualExecutor.submit(new Task(i, io, virtualCompleted));
            }

            // Wait for all tasks to complete
            virtualExecutor.shutdown();
            boolean virtualFinished = virtualExecutor.awaitTermination(30, TimeUnit.SECONDS);
            System.out.println("All virtual tasks finished: " + virtualFinished);
        }
        long virtualEndTime = System.currentTimeMillis();
        long virtualDuration = virtualEndTime - virtualStartTime;

        System.out.println("\n✓ Virtual Threads Benchmark Complete:");
        System.out.println(String.format("  Tasks completed: %d", virtualCompleted.get()));
        System.out.println(String.format("  Wall-clock time: %.2f seconds", virtualDuration / 1000.0));
        System.out.println(String.format("  Peak thread count: ~10,000 (one VT per task)"));
        System.out.println(String.format("  Memory usage: ~50 MB (5 KB per VT on heap)"));

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // COMPARISON
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("COMPARISON SUMMARY");
        System.out.println("=".repeat(80));

        double speedup = (double) platformDuration / virtualDuration;
        System.out.println(String.format("Platform threads time:   %.2f seconds", platformDuration / 1000.0));
        System.out.println(String.format("Virtual threads time:    %.2f seconds", virtualDuration / 1000.0));
        System.out.println(String.format("Speedup factor:          %.1fx faster", speedup));
        System.out.println();
        System.out.println("💡 KEY INSIGHT:");
        System.out.println("Virtual threads achieved the same throughput ~" + (int)speedup + "x faster because:");
        System.out.println("  • During the 100ms I/O block, platform threads sit idle (OS thread not freed)");
        System.out.println("  • Virtual threads unmount when blocked, freeing the carrier OS thread");
        System.out.println("  • With only ~10 OS cores, the JVM ForkJoinPool efficiently schedules");
        System.out.println("    10,000 virtual threads by rapidly mounting/unmounting them");
        System.out.println();
        System.out.println("Watch this with JConsole or VisualVM:");
        System.out.println("  • Platform: Thread count stays near 200 (pool size)");
        System.out.println("  • Virtual:  Thread count can spike to 10,000 but CPU usage stays low");
        System.out.println("=".repeat(80));
    }
}
