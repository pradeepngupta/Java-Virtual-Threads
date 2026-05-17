package com.bigjex.vt.demos; /**
 * DEEP DIVE: Why Synchronized Blocks Pin Virtual Threads
 * While ReentrantLock Does Not
 * 
 * This document explains the JVM internals and the fundamental difference
 * between the two locking mechanisms.
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 1: WHAT IS PINNING? (The Quick Recap)
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * A virtual thread is PINNED when it CANNOT UNMOUNT from its carrier OS thread.
 * 
 * Normal flow:
 *   VT runs bytecode on carrier thread → blocks on I/O → JVM unmounts VT
 *   → Stack saved to heap → Carrier thread freed for next VT
 * 
 * Pinned flow:
 *   VT runs bytecode on carrier thread → blocks inside synchronized
 *   → JVM CANNOT unmount (it doesn't know if the lock is held)
 *   → Carrier thread STUCK, waiting
 *   → Other VTs cannot use this carrier thread
 * 
 * At scale (10,000 VTs with only 10 carrier threads), pinning causes massive
 * throughput collapse because the thread pool is exhausted waiting on locks.
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 2: SYNCHRONIZED BLOCK INTERNALS (The JVM Side)
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * SYNCHRONIZED uses a MONITOR LOCK (also called an "object monitor").
 * 
 * Every Java object has a hidden "monitor" in its header:
 * 
 *   class MyClass {
 *       // ... fields ...
 *       // Hidden in every object instance:
 *       // - ObjectMonitor* (pointer to monitor lock)
 *       // - Lock state (locked, unlocked)
 *       // - Owner thread ID
 *       // - Wait queue for threads blocked on this lock
 *   }
 * 
 * When you write:
 * 
 *   synchronized (obj) {
 *       // critical section
 *   }
 * 
 * The JVM bytecode becomes:
 * 
 *   monitorenter   obj     // Acquire the lock on 'obj'
 *   // ... critical section bytecode ...
 *   monitorexit    obj     // Release the lock
 * 
 * ════════════════════════════════════════════════════════════════════════════
 * KEY PROBLEM: THE NATIVE JAVA MONITOR
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * The ObjectMonitor is implemented at the NATIVE LEVEL in the JVM (C++ code).
 * 
 * When a thread blocks on a monitor (monitorenter on a locked object):
 * 
 *   1. The JVM calls into NATIVE CODE (ObjectMonitor::enter())
 *   2. Native code does OS-level synchronization (futex on Linux, etc.)
 *   3. Thread enters OS WAIT state
 *   4. JVM **CANNOT** safely unmount the thread because:
 *      - The native code might inspect the thread structure
 *      - The native code might hold pointers to the stack
 *      - The native code expects the thread to remain on a real OS thread
 * 
 * The JVM has NO WAY to know when native code is done with the thread
 * references, so it conservatively keeps the thread PINNED.
 * 
 * Think of it like this:
 * 
 *   synchronized {
 *       // Inside here, you're talking to native code
 *       // The JVM can't safely move you to another OS thread
 *       // because native code has pointers to your stack
 *   }
 * 
 * This is why it's called a MONITOR LOCK — it's inherently tied to OS-level
 * synchronization primitives (mutexes, condition variables).
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 3: REENTRANT LOCK INTERNALS (The Java Side)
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * ReentrantLock is implemented ENTIRELY IN JAVA (java.util.concurrent.locks).
 * 
 * It uses:
 *   - java.util.concurrent.locks.AbstractQueuedSynchronizer (AQS)
 *   - Atomic operations (java.util.concurrent.atomic.AtomicReference)
 *   - LockSupport.park() / unpark() for thread waiting
 * 
 * Here's the architecture:
 * 
 *   ReentrantLock
 *       └─→ AbstractQueuedSynchronizer
 *           ├─→ AtomicReference<Node> (the lock state)
 *           ├─→ Volatile int (lock count for reentrancy)
 *           └─→ AbstractQueuedSynchronizer.Node (wait queue)
 * 
 * When you call lock.lock():
 * 
 *   1. Try to atomically set the lock state (compareAndSet, all in Java)
 *   2. If locked, create a Node and add to wait queue (all in Java)
 *   3. Call LockSupport.park() to wait
 * 
 * ════════════════════════════════════════════════════════════════════════════
 * KEY DIFFERENCE: LOCKSUPPORT.PARK() IS VIRTUAL-THREAD-AWARE
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * LockSupport.park() is a SPECIAL JVM INTRINSIC that the JVM recognizes.
 * 
 * When a virtual thread calls LockSupport.park():
 * 
 *   1. JVM recognizes it (intrinsic operation)
 *   2. JVM **UNMOUNTS** the virtual thread from the carrier
 *   3. Virtual thread state is saved
 *   4. Carrier OS thread is freed
 *   5. When lock is released, LockSupport.unpark() is called
 *   6. Virtual thread is REMOUNTED on a (possibly different) carrier thread
 * 
 * This is the MAGIC: The JVM knows about LockSupport, so it can safely
 * unmount virtual threads. But with synchronized/monitors, the JVM doesn't
 * know about the native code, so it must conservatively PIN.
 * 
 * Pseudocode of what happens:
 * 
 *   // In java.util.concurrent.locks.AbstractQueuedSynchronizer:
 *   boolean tryLock() {
 *       // Fast path: try to acquire atomically (all Java)
 *       if (compareAndSetState(0, 1)) {
 *           setExclusiveOwnerThread(Thread.currentThread());
 *           return true;
 *       }
 *       // Slow path: queue up and wait
 *       acquireQueued(addWaiter(Node.EXCLUSIVE), arg);
 *       return true;
 *   }
 *   
 *   // In the JVM's implementation of LockSupport.park():
 *   private static void park(boolean isAbsolute, long time) {
 *       // JVM RECOGNIZES THIS
 *       // For virtual threads:
 *       //   1. Unmount from carrier
 *       //   2. Save stack
 *       //   3. Free carrier
 *       //   4. Put VT in WAITING state
 *       // 
 *       // For platform threads:
 *       //   1. Call native OS park (futex on Linux)
 *   }
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 4: VISUAL COMPARISON
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * SYNCHRONIZED LOCK (causes pinning):
 * 
 *   Virtual Thread State Machine:
 *   
 *   RUNNABLE
 *       │
 *       ├─→ [monitorenter obj]
 *       │
 *       ├─→ JVM calls ObjectMonitor::enter() [NATIVE C++ CODE]
 *       │
 *       ├─→ Lock is held by another thread
 *       │
 *       ├─→ JVM calls OS wait() (futex, etc.)
 *       │
 *       ├─→ Thread enters OS WAITING state
 *       │
 *       │   ⚠️  JVM CANNOT UNMOUNT HERE because:
 *       │   - Native code (ObjectMonitor) might have pointers to this thread's stack
 *       │   - Native code might be inspecting thread-local data
 *       │   - JVM conservatively assumes native code will touch the thread
 *       │
 *       ├─→ PINNED TO CARRIER OS THREAD
 *       │
 *       ├─→ [Wait for lock to be released]
 *       │
 *       ├─→ OS wakes up the thread
 *       │
 *       ├─→ [monitorexit obj]
 *       │
 *       └─→ Back to RUNNABLE
 * 
 * 
 * REENTRANT LOCK (no pinning):
 * 
 *   Virtual Thread State Machine:
 *   
 *   RUNNABLE
 *       │
 *       ├─→ [lock.lock()]
 *       │
 *       ├─→ AQS.tryAcquire() [PURE JAVA]
 *       │
 *       ├─→ Lock is held by another thread
 *       │
 *       ├─→ Create Node, add to queue [PURE JAVA]
 *       │
 *       ├─→ Call LockSupport.park() [JVM INTRINSIC]
 *       │
 *       │   ✅ JVM RECOGNIZES LockSupport.park()!
 *       │   - This is a KNOWN pattern
 *       │   - JVM knows it's safe to unmount
 *       │   - All state is in Java objects (not native code)
 *       │
 *       ├─→ JVM UNMOUNTS virtual thread from carrier
 *       ├─→ Stack saved to heap
 *       ├─→ Carrier OS thread freed
 *       │
 *       ├─→ Virtual thread enters PARKED state
 *       │
 *       ├─→ [Lock holder releases lock and calls unpark()]
 *       │
 *       ├─→ Virtual thread remounted on a (possibly different) carrier
 *       │
 *       ├─→ Back to RUNNABLE
 *       │
 *       └─→ [lock.unlock()]
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 5: THE CORE DIFFERENCE IN ONE SENTENCE
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * SYNCHRONIZED:
 *   "Lock implemented at NATIVE level (ObjectMonitor).
 *    JVM can't safely unmount thread while native code might touch it.
 *    PINNED."
 * 
 * REENTRANT LOCK:
 *   "Lock implemented in PURE JAVA (AQS + LockSupport).
 *    JVM recognizes LockSupport.park() intrinsic.
 *    Safe to unmount. NOT PINNED."
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 6: WHAT ABOUT JDK 25? (JEP 491 — SYNCHRONIZED UNPINNED)
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * JDK 25 introduces JEP 491: Synchronized no longer pins virtual threads.
 * 
 * HOW? The JVM team rewrote the monitor lock implementation to be
 * VIRTUAL-THREAD-AWARE, similar to LockSupport:
 * 
 * OLD (JDK 21-24):
 *   synchronized { } → ObjectMonitor (native) → PINS virtual thread
 * 
 * NEW (JDK 25):
 *   synchronized { } → Lightweight virtual-thread-aware monitor → NO PIN
 * 
 * What changed:
 * 
 *   1. JVM detects synchronized block entry
 *   2. Instead of calling native ObjectMonitor::enter(),
 *      JVM uses a NEW lightweight synchronization mechanism
 *   3. This mechanism understands virtual threads
 *   4. Uses the same unmount/remount pattern as LockSupport.park()
 *   5. RESULT: synchronized no longer pins!
 * 
 * This is why JDK 25 is such a big deal for virtual threads:
 * 
 *   Legacy code with synchronized { }
 *   └─→ JDK 21-24: Will pin and degrade performance
 *   └─→ JDK 25+:   Works perfectly, no pinning!
 */

// ═══════════════════════════════════════════════════════════════════════════════
// PART 7: CODE DEMONSTRATION
// ═══════════════════════════════════════════════════════════════════════════════

import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class LockMechanismComparison {

    /**
     * SYNCHRONIZED: Uses ObjectMonitor (native monitor lock)
     */
    static class SynchronizedExample {
        private int counter = 0;

        // The 'synchronized' keyword generates bytecode with monitorenter/monitorexit
        // These delegate to ObjectMonitor, which is NATIVE (C++) code
        synchronized void increment() throws InterruptedException {
            counter++;
            // ⚠️  If another thread holds the lock, you'll call into:
            //     ObjectMonitor::enter() [C++ code]
            //     └─→ OS futex/mutex call
            //     └─→ Thread pins to OS thread
            //     └─→ Virtual thread CANNOT unmount
            
            Thread.sleep(100);  // This sleep is PINNED!
        }

        // Under the hood, the JVM compiles this to:
        //
        // synchronized (this) {
        //     counter++;
        //     Thread.sleep(100);
        // }
        //
        // Becomes:
        //
        // monitorenter    <object reference>
        // aload_0
        // getfield counter
        // iconst_1
        // iadd
        // putfield counter
        // ldc 100
        // invokestatic Thread.sleep(J)V
        // monitorexit     <object reference>
        //
        // Where monitorenter/monitorexit are JVM intrinsics that call:
        // → ObjectMonitor::enter()  [native implementation in hotspot/src/share/vm/runtime/objectMonitor.cpp]
    }

    /**
     * REENTRANT LOCK: Uses AbstractQueuedSynchronizer (pure Java)
     */
    static class ReentrantLockExample {
        private final ReentrantLock lock = new ReentrantLock();
        private int counter = 0;

        void increment() throws InterruptedException {
            lock.lock();  // ← AbstractQueuedSynchronizer.tryAcquire() [JAVA]
            try {         //
                counter++;
                // ✅ If another thread holds the lock, lock.lock() calls:
                //     AbstractQueuedSynchronizer.acquire()
                //     └─→ tryAcquire() (pure Java atomics)
                //     └─→ addWaiter() (pure Java queue)
                //     └─→ LockSupport.park() [JVM INTRINSIC]
                //     └─→ Virtual thread can UNMOUNT!
                
                Thread.sleep(100);  // This sleep is NOT pinned!
            } finally {
                lock.unlock();  // ← AbstractQueuedSynchronizer.release() [JAVA]
            }
        }

        // Under the hood:
        //
        // lock.lock() calls AQS.acquire(1):
        //
        // public final void acquire(int arg) {
        //     if (!tryAcquire(arg) &&
        //         acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        //         selfInterrupt();
        // }
        //
        // tryAcquire() uses:
        //   compareAndSetState(expect, update) [atomic compare-and-swap, pure Java]
        //   setExclusiveOwnerThread(t)          [volatile assignment, pure Java]
        //
        // acquireQueued() calls:
        //   LockSupport.park()  [← JVM INTRINSIC, understands virtual threads!]
        //
        // When lock is released, unpark() is called:
        //   LockSupport.unpark(t)  [← Virtual thread is remounted]
    }

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("Lock Mechanism Comparison");
        System.out.println("════════════════════════════════════════════════════════════════\n");

        System.out.println("1. SYNCHRONIZED BLOCK:");
        System.out.println("   Uses: ObjectMonitor (native monitor lock)");
        System.out.println("   Location: hotspot/src/share/vm/runtime/objectMonitor.cpp");
        System.out.println("   Blocking mechanism: OS futex/mutex (kernel-level)");
        System.out.println("   Virtual thread behavior: PINNED ❌");
        System.out.println("   Virtual thread unmount: CANNOT (native code might touch thread)");
        System.out.println();

        System.out.println("2. REENTRANT LOCK:");
        System.out.println("   Uses: AbstractQueuedSynchronizer + LockSupport (pure Java)");
        System.out.println("   Location: java/util/concurrent/locks/AbstractQueuedSynchronizer.java");
        System.out.println("   Blocking mechanism: LockSupport.park() (JVM intrinsic)");
        System.out.println("   Virtual thread behavior: NOT PINNED ✅");
        System.out.println("   Virtual thread unmount: CAN (JVM knows LockSupport pattern)");
        System.out.println();

        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("WHY THE DIFFERENCE?");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("ObjectMonitor is NATIVE C++ code.");
        System.out.println("When a thread enters ObjectMonitor::enter():");
        System.out.println("  • Native code might inspect the thread structure");
        System.out.println("  • Native code might store pointers to the stack");
        System.out.println("  • JVM CANNOT know when native code is done");
        System.out.println("  • JVM conservatively PINS the thread");
        System.out.println();
        System.out.println("LockSupport.park() is a RECOGNIZED JVM INTRINSIC.");
        System.out.println("When a thread calls LockSupport.park():");
        System.out.println("  • JVM RECOGNIZES this pattern");
        System.out.println("  • JVM KNOWS it's safe to unmount");
        System.out.println("  • All state is in Java objects, not native code");
        System.out.println("  • Virtual thread is UNMOUNTED");
        System.out.println();

        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("JDK 25 SOLUTION (JEP 491)");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("JDK 25 rewrites synchronized to be virtual-thread-aware:");
        System.out.println("  OLD: synchronized { } → ObjectMonitor (native) → PINS");
        System.out.println("  NEW: synchronized { } → Lightweight VT-aware monitor → NO PIN");
        System.out.println();
        System.out.println("IMPACT: Legacy code with synchronized gets a free performance fix!");
        System.out.println();
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PART 8: ARCHITECTURE DIAGRAM
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * 
 * SYNCHRONIZED BLOCKING STACK:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 *   Application Code
 *        │
 *        ├─→ synchronized (obj) { ... }
 *        │
 *        ├─→ [BYTECODE: monitorenter]
 *        │
 *   JVM Layer
 *        │
 *        ├─→ ObjectMonitor::enter()
 *        │
 *   NATIVE C++ CODE (hotspot/vm/runtime/objectMonitor.cpp)
 *        │
 *        ├─→ Lock is held
 *        │
 *        ├─→ Call to OS synchronization primitive
 *        │
 *   KERNEL (Linux/Windows/macOS)
 *        │
 *        ├─→ futex() / WaitForSingleObject() / pthread_mutex_lock()
 *        │
 *        ├─→ Thread WAITS in OS scheduler
 *        │
 *        └─→ JVM PINS VIRTUAL THREAD (cannot safely unmount)
 *             ⚠️  Native code might touch the thread!
 * 
 * 
 * REENTRANT LOCK BLOCKING STACK:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 *   Application Code
 *        │
 *        ├─→ lock.lock()
 *        │
 *   Java Library Code (java.util.concurrent.locks)
 *        │
 *        ├─→ AbstractQueuedSynchronizer.acquire()
 *        │
 *        ├─→ tryAcquire() [pure Java atomics]
 *        │
 *        ├─→ addWaiter() [pure Java queue]
 *        │
 *        ├─→ LockSupport.park()
 *        │
 *   JVM Intrinsic Recognition
 *        │
 *        ├─→ JVM RECOGNIZES LockSupport.park()
 *        │
 *   Virtual Thread Handling
 *        │
 *        ├─→ Virtual thread UNMOUNTS from carrier
 *        │
 *        ├─→ Stack saved to heap
 *        │
 *        ├─→ Carrier OS thread FREED
 *        │
 *        ├─→ Virtual thread enters PARKED state
 *        │
 *        └─→ ✅ NO PINNING (safe to park/unpark)
 *             Virtual thread can be remounted on any carrier!
 * 
 * 
 * KEY INSIGHT:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The deeper you go in the synchronized stack, the more "native" and "opaque"
 * the code becomes. The JVM loses visibility and must conservatively PIN.
 * 
 * The ReentrantLock stack stays in PURE JAVA until the very last step
 * (LockSupport.park()), which is a KNOWN JVM INTRINSIC. The JVM knows exactly
 * what LockSupport.park() does and can unmount safely.
 * 
 */