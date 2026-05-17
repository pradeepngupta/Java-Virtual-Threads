package com.bigjex.vt.demos;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demo 2: Thread Pinning Detection & ReentrantLock Fix
 * 
 * This demo shows how synchronized blocks cause virtual threads to PING to their carrier OS thread,
 * preventing unmounting and defeating the whole purpose of virtual threads.
 * 
 * Execution:
 *   1. Run with JVM flag: -Djdk.tracePinnedThreads=short (or 'full' for detailed stack traces)
 *   2. Observe pinning warnings in the console output
 *   3. Comment out the synchronized section
 *   4. Uncomment the ReentrantLock section
 *   5. Run again with the same JVM flag
 *   6. Observe that pinning warnings disappear
 * 
 * Expected results:
 *   - With synchronized: "Pinning:" messages appear in output
 *   - With ReentrantLock: No pinning warnings
 * 
 * Note: In JDK 25, synchronized no longer pins by default (JEP 491).
 * This demo remains valuable for understanding the concept and for JDK 21-24 environments.
 */
public class PinningDetection {

    /**
     * A helper class using SYNCHRONIZED BLOCKS — this will cause pinning
     */
    static class SynchronizedHelper {
        private int counter = 0;

        // ⚠️  This synchronized block pins the virtual thread!
        synchronized void incrementAndBlock() throws InterruptedException {
            counter++;
            // Simulate some blocking I/O inside the synchronized block
            // With synchronized, the VT cannot unmount — the OS thread is pinned
            Thread.sleep(100);
            System.out.println("  synchronized completed, counter = " + counter);
        }
    }

    /**
     * A helper class using REENTRANT LOCK — no pinning
     */
    static class ReentrantLockHelper {
        private final ReentrantLock lock = new ReentrantLock();
        private int counter = 0;

        // ✅ This ReentrantLock does NOT pin the virtual thread
        void incrementAndBlock() throws InterruptedException {
            lock.lock();
            try {
                counter++;
                // Virtual thread can now unmount during this sleep,
                // freeing the carrier OS thread for other VTs
                Thread.sleep(100);
                System.out.println("  reentrant lock completed, counter = " + counter);
            } finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=".repeat(80));
        System.out.println("Demo 2: Thread Pinning Detection & ReentrantLock Fix");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("Run this with JVM flag: -Djdk.tracePinnedThreads=short");
        System.out.println("(Or 'full' for detailed stack traces showing where pinning occurs)");
        System.out.println();
        System.out.println("=".repeat(80));

        int taskCount = 10;

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // TEST 1: Synchronized Block (PINS virtual threads)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n⚠️  TEST 1: SYNCHRONIZED BLOCK (causes pinning)");
        System.out.println("-".repeat(80));
        System.out.println("Submitting " + taskCount + " virtual threads to call synchronized method...");
        System.out.println("Watch for 'Pinning:' warnings in the output below:\n");

        SynchronizedHelper syncHelper = new SynchronizedHelper();
        long syncStartTime;
        boolean awaitedTermination;
        try (ExecutorService syncExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

            syncStartTime = System.currentTimeMillis();

            for (int i = 0; i < taskCount; i++) {
                final int taskId = i;
                syncExecutor.submit(() -> {
                    try {
                        System.out.println("[VT-" + taskId + "] About to call synchronized method...");
                        syncHelper.incrementAndBlock();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            syncExecutor.shutdown();
            awaitedTermination = syncExecutor.awaitTermination(30, TimeUnit.SECONDS);
        }
        System.out.println("\nAll synchronized tasks completed: " + awaitedTermination);
        long syncDuration = System.currentTimeMillis() - syncStartTime;

        System.out.println("\n✓ Synchronized test completed in " + (syncDuration / 1000.0) + " seconds");
        System.out.println("  Note: Check the 'Pinning:' output above to see where pinning occurred.");
        System.out.println("  The virtual threads were PINNED during the Thread.sleep() inside synchronized.");

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // TEST 2: ReentrantLock (NO pinning)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n✅ TEST 2: REENTRANT LOCK (no pinning)");
        System.out.println("-".repeat(80));
        System.out.println("Submitting " + taskCount + " virtual threads to call ReentrantLock method...");
        System.out.println("Watch the output — no 'Pinning:' warnings should appear:\n");

        ReentrantLockHelper lockHelper = new ReentrantLockHelper();
        long lockStartTime;
        try (ExecutorService lockExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

            lockStartTime = System.currentTimeMillis();

            for (int i = 0; i < taskCount; i++) {
                final int taskId = i;
                lockExecutor.submit(() -> {
                    try {
                        System.out.println("[VT-" + taskId + "] About to call reentrant lock method...");
                        lockHelper.incrementAndBlock();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            lockExecutor.shutdown();
            boolean awaitedTermination1 = lockExecutor.awaitTermination(30, TimeUnit.SECONDS);
            System.out.println("\nAll ReentrantLock tasks completed: " + awaitedTermination1);
        }
        long lockDuration = System.currentTimeMillis() - lockStartTime;

        System.out.println("\n✓ ReentrantLock test completed in " + (lockDuration / 1000.0) + " seconds");
        System.out.println("  ✅ No 'Pinning:' warnings in the output above!");
        System.out.println("  The virtual threads were able to UNMOUNT and REMOUNT during the lock.");

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // SUMMARY & JDK 25 NOTE
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        System.out.println("\n❌ SYNCHRONIZED BLOCKS:");
        System.out.println("   • Pin the virtual thread to its carrier OS thread");
        System.out.println("   • During a blocking operation (I/O, lock wait), the OS thread sits idle");
        System.out.println("   • Other virtual threads cannot use that carrier thread");
        System.out.println("   • Massive throughput degradation at scale");

        System.out.println("\n✅ REENTRANT LOCKS:");
        System.out.println("   • Do NOT pin the virtual thread");
        System.out.println("   • Virtual thread can unmount during lock waits");
        System.out.println("   • Carrier OS thread freed to run other virtual threads");
        System.out.println("   • Same behavior as before, but with benefits of virtual threads");

        System.out.println("\n📢 JDK 25 UPDATE (JEP 491):");
        System.out.println("   • Synchronized blocks NO LONGER PIN virtual threads by default");
        System.out.println("   • Legacy code gets this fix for free on upgrade");
        System.out.println("   • This is THE biggest reason to move to JDK 25!");

        System.out.println("\n💡 DETECTION TOOL:");
        System.out.println("   Use: -Djdk.tracePinnedThreads=short (or 'full' for stack traces)");
        System.out.println("   This flag immediately shows you where pinning happens in your code");

        System.out.println("\n🔧 MIGRATION STRATEGY:");
        System.out.println("   1. Run with -Djdk.tracePinnedThreads=full");
        System.out.println("   2. Find all synchronized blocks in hot paths");
        System.out.println("   3. Replace with ReentrantLock for virtual thread code paths");
        System.out.println("   4. Or just upgrade to JDK 25 and enjoy the fix!");

        System.out.println("\n" + "=".repeat(80));
    }
}
