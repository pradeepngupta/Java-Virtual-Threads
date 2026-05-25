import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ════════════════════════════════════════════════════════════════════════════
 * Demo 1: The Complete Truth — Platform vs Virtual Threads
 * Three-Part Benchmark: CPU-Bound | I/O-Bound | Mixed
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS DEMO PROVES:
 *   Part A — CPU-Bound:  Virtual threads are AT PAR with platform threads.
 *                        No advantage. The scheduler overhead is the same.
 *                        VTs are not magic — they need something to unmount ON.
 *
 *   Part B — I/O-Bound:  Virtual threads are 10–50x FASTER.
 *                        Platform threads sit idle during the blocking wait.
 *                        Virtual threads unmount, free the carrier, and let
 *                        another VT run. The carrier is never idle.
 *
 *   Part C — Mixed:      Real-world workloads are a blend.
 *                        Even 20% I/O shows meaningful VT advantage.
 *                        At 80% I/O (typical microservice), VTs dominate.
 *
 * HOW TO RUN IN IntelliJ IDEA:
 *   Right-click → Run 'Demo1_ThroughputBenchmark.main()'
 *   Optionally open JConsole/VisualVM to watch thread counts live.
 *
 * EXPECTED OUTPUT (approximate, varies by hardware):
 *   CPU-Bound:   Platform ~2.0s | Virtual ~2.1s  → ratio ~1.0x  (AT PAR)
 *   I/O-Bound:   Platform ~5.0s | Virtual ~0.5s  → ratio ~10x   (VT WINS)
 *   Mixed 50/50: Platform ~3.5s | Virtual ~1.2s  → ratio ~3x    (VT WINS)
 *
 * JDK REQUIREMENT: Java 21 or higher
 * ════════════════════════════════════════════════════════════════════════════
 */
public class Demo1_ThroughputBenchmark {

    // ── Tuning knobs — adjust for your machine ────────────────────────────────
    static final int  TASK_COUNT        = 1_000;   // total tasks per benchmark
    static final int  PLATFORM_THREADS  = 200;     // fixed pool size
    static final long IO_BLOCK_MS       = 100;     // simulated I/O wait per task
    static final int  CPU_ITERATIONS    = 500_000; // math iterations per CPU task

    // ─────────────────────────────────────────────────────────────────────────
    // TASK TYPES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CPU-BOUND TASK
     * Does real computation — no I/O, no sleep.
     * The thread is active the entire time.
     * Virtual threads have NOTHING to unmount on.
     */
    static long cpuTask(int taskId) {
        // Simulate CPU-intensive computation: sum of square roots
        // This keeps the CPU genuinely busy — not just looping
        double result = 0;
        for (int i = 1; i <= CPU_ITERATIONS; i++) {
            result += Math.sqrt(i) * Math.sin(i) * Math.cos(i);
        }
        // Return to prevent dead-code elimination by JIT
        return (long) result + taskId;
    }

    /**
     * I/O-BOUND TASK
     * Blocks on Thread.sleep() — simulating a DB query or HTTP call.
     * The thread is completely idle during the wait.
     * Virtual threads UNMOUNT during the sleep — carrier is freed.
     * Platform threads HOLD the OS thread the entire time — wasteful.
     */
    static void ioTask(int taskId) throws InterruptedException {
        Thread.sleep(IO_BLOCK_MS); // Simulated blocking I/O
    }

    /**
     * MIXED TASK
     * A realistic task: some CPU work + some I/O wait.
     * Represents a typical microservice handler:
     *   validate request (CPU) → call database (I/O) → transform response (CPU)
     *
     * @param ioPct  percentage of total time spent in I/O  (0–100)
     *               e.g. 50 = half CPU work, half I/O wait
     */
    static long mixedTask(int taskId, int ioPct) throws InterruptedException {
        // CPU portion — light computation proportional to (100 - ioPct)
        int cpuIter = CPU_ITERATIONS / 10 * (100 - ioPct) / 100;
        double result = 0;
        for (int i = 1; i <= Math.max(cpuIter, 1); i++) {
            result += Math.sqrt(i) * Math.sin(i);
        }
        // I/O portion — blocking wait proportional to ioPct
        long ioMs = IO_BLOCK_MS * ioPct / 100;
        if (ioMs > 0) Thread.sleep(ioMs);
        return (long) result + taskId;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BENCHMARK ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface CheckedTask {
        void run(int taskId) throws Exception;
    }

    static long runBenchmark(String label, ExecutorService executor,
                              CheckedTask task, int taskCount) throws InterruptedException {
        AtomicInteger completed = new AtomicInteger(0);
        AtomicLong    checksum  = new AtomicLong(0); // prevents JIT dead-code elimination

        CountDownLatch latch = new CountDownLatch(taskCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            executor.submit(() -> {
                try {
                    task.run(taskId);
                    completed.incrementAndGet();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        System.out.printf("    %-40s  completed: %5d  time: %6.2f s%n",
            label, completed.get(), duration / 1000.0);

        return duration;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT PRINTER
    // ─────────────────────────────────────────────────────────────────────────

    static void printResult(String scenario,
                             long platformMs, long virtualMs) {
        double ratio = (double) platformMs / virtualMs;
        String verdict;
        String explanation;

        if (ratio < 1.10) {
            verdict     = "AT PAR";
            explanation = "Virtual threads offer NO advantage here.\n"
                        + "    There is no I/O to unmount on.\n"
                        + "    The carrier thread is busy the whole time.\n"
                        + "    Both thread types are constrained by CPU cores.";
        } else if (ratio < 2.0) {
            verdict     = "VT SLIGHTLY FASTER";
            explanation = "Small VT advantage — some I/O unblocking happening.\n"
                        + "    Mixed workloads show early benefit as I/O% rises.";
        } else {
            verdict     = String.format("VT WINS (%.1fx faster)", ratio);
            explanation = "Platform threads idle during I/O — OS thread wasted.\n"
                        + "    Virtual threads unmount, carrier serves other VTs.\n"
                        + "    This is the core promise of Project Loom delivered.";
        }

        System.out.println();
        System.out.printf("  %-16s  Platform: %5.2fs  |  Virtual: %5.2fs  |  Ratio: %5.2fx  |  %s%n",
            scenario,
            platformMs / 1000.0,
            virtualMs / 1000.0,
            ratio,
            verdict);
        System.out.println("    --> " + explanation);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=".repeat(80));
        System.out.println("  Demo 1: The Complete Truth — CPU vs I/O vs Mixed Workloads");
        System.out.println("  Platform Threads vs Virtual Threads");
        System.out.println("=".repeat(80));
        System.out.printf("%n  Tasks per benchmark : %,d%n", TASK_COUNT);
        System.out.printf("  Platform pool size  : %d threads%n", PLATFORM_THREADS);
        System.out.printf("  I/O block duration  : %d ms per task%n", IO_BLOCK_MS);
        System.out.printf("  CPU iterations      : %,d per task%n%n", CPU_ITERATIONS);
        System.out.println("  Running all benchmarks — this will take ~30-60 seconds...");
        System.out.println("  (Open JConsole or VisualVM now to watch thread counts live)");
        System.out.println();

        // ── Warm up the JIT so benchmarks are fair ────────────────────────────
        System.out.println("  [Warming up JIT...]");
        ExecutorService warmup = Executors.newFixedThreadPool(10);
        CountDownLatch warmupLatch = new CountDownLatch(100);
        for (int i = 0; i < 100; i++) {
            final int id = i;
            warmup.submit(() -> { cpuTask(id); warmupLatch.countDown(); });
        }
        warmupLatch.await(30, TimeUnit.SECONDS);
        warmup.shutdown();
        warmup.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("  [JIT warm-up complete]\n");

        // ════════════════════════════════════════════════════════════════════
        // PART A — CPU-BOUND
        // The key point: VTs offer ZERO advantage here.
        // A virtual thread doing pure computation never unmounts.
        // The ForkJoinPool carrier count = CPU cores either way.
        // ════════════════════════════════════════════════════════════════════
        System.out.println("━".repeat(80));
        System.out.println("  PART A: CPU-BOUND TASKS  (pure computation, no I/O)");
        System.out.println("  Hypothesis: Virtual threads will be AT PAR with platform threads.");
        System.out.println("  Why: No blocking = no unmounting = no carrier freed = no VT advantage.");
        System.out.println("━".repeat(80));

        long platformCpu = runBenchmark(
            "Platform (FixedPool/" + PLATFORM_THREADS + ")",
            Executors.newFixedThreadPool(PLATFORM_THREADS),
            taskId -> cpuTask(taskId),
            TASK_COUNT
        );

        long virtualCpu = runBenchmark(
            "Virtual  (per-task executor)",
            Executors.newVirtualThreadPerTaskExecutor(),
            taskId -> cpuTask(taskId),
            TASK_COUNT
        );

        // ════════════════════════════════════════════════════════════════════
        // PART B — I/O-BOUND
        // The key point: VTs are dramatically faster here.
        // Every task blocks for 100ms. Platform threads idle.
        // Virtual threads unmount, carrier serves next VT immediately.
        // ════════════════════════════════════════════════════════════════════
        System.out.println();
        System.out.println("━".repeat(80));
        System.out.println("  PART B: I/O-BOUND TASKS  (blocking sleep, no computation)");
        System.out.println("  Hypothesis: Virtual threads will be dramatically faster.");
        System.out.println("  Why: 100ms block = platform thread idles. VT unmounts, carrier freed.");
        System.out.println("━".repeat(80));

        long platformIo = runBenchmark(
            "Platform (FixedPool/" + PLATFORM_THREADS + ")",
            Executors.newFixedThreadPool(PLATFORM_THREADS),
            taskId -> ioTask(taskId),
            TASK_COUNT
        );

        long virtualIo = runBenchmark(
            "Virtual  (per-task executor)",
            Executors.newVirtualThreadPerTaskExecutor(),
            taskId -> ioTask(taskId),
            TASK_COUNT
        );

        // ════════════════════════════════════════════════════════════════════
        // PART C — MIXED (50% CPU / 50% I/O)
        // Represents a realistic microservice handler.
        // ════════════════════════════════════════════════════════════════════
        System.out.println();
        System.out.println("━".repeat(80));
        System.out.println("  PART C: MIXED TASKS  (50% CPU computation + 50% I/O blocking)");
        System.out.println("  Hypothesis: VT advantage is proportional to I/O percentage.");
        System.out.println("  Why: Even partial I/O lets VTs unmount for part of each task.");
        System.out.println("━".repeat(80));

        long platformMix = runBenchmark(
            "Platform (FixedPool/" + PLATFORM_THREADS + ")",
            Executors.newFixedThreadPool(PLATFORM_THREADS),
            taskId -> mixedTask(taskId, 50),
            TASK_COUNT
        );

        long virtualMix = runBenchmark(
            "Virtual  (per-task executor)",
            Executors.newVirtualThreadPerTaskExecutor(),
            taskId -> mixedTask(taskId, 50),
            TASK_COUNT
        );

        // ════════════════════════════════════════════════════════════════════
        // FINAL COMPARISON TABLE
        // ════════════════════════════════════════════════════════════════════
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("  FINAL RESULTS");
        System.out.println("=".repeat(80));

        printResult("CPU-Bound",    platformCpu, virtualCpu);
        printResult("I/O-Bound",    platformIo,  virtualIo);
        printResult("Mixed (50/50)", platformMix, virtualMix);

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("  THE LESSON IN THREE SENTENCES:");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("  1. CPU-bound tasks:  Virtual threads = platform threads. AT PAR.");
        System.out.println("     VTs have nothing to unmount on. Both are constrained by CPU cores.");
        System.out.println();
        System.out.println("  2. I/O-bound tasks:  Virtual threads DOMINATE.");
        System.out.println("     Every millisecond a platform thread idles, a VT is already");
        System.out.println("     unmounted and its carrier is serving someone else.");
        System.out.println();
        System.out.println("  3. Real workloads are I/O-heavy (DB, network, file, downstream APIs).");
        System.out.println("     That is exactly where virtual threads were designed to win.");
        System.out.println();
        System.out.println("  PRADEEP'S 2005 IVR STORY:");
        System.out.println("  Each phone call dialled = one thread blocking on a native call.");
        System.out.println("  With virtual threads + pure Java I/O: this demo shows the fix.");
        System.out.println("  With native dialler calls (JNI): pinning still occurs. (See Demo 2)");
        System.out.println();
        System.out.println("  USE VIRTUAL THREADS FOR: HTTP servers, JDBC, REST clients,");
        System.out.println("                            file I/O, message consumers.");
        System.out.println("  AVOID VIRTUAL THREADS FOR: image processing, ML inference,");
        System.out.println("                              cryptography, sorting large datasets.");
        System.out.println("=".repeat(80));
    }
}