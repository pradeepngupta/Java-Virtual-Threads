# Java Virtual Threads — Live Demos Setup Guide

This folder contains three production-ready Java demo programs that showcase Virtual Threads, Pinning Detection, and Structured Concurrency.

## System Requirements

- **JDK Version**: Java 21 or higher (Java 25 recommended for optimal Structured Concurrency support)
- **IDE**: IntelliJ IDEA (any recent version)
- **Runtime Tools** (recommended): JConsole or VisualVM for monitoring threads

## Quick Setup in IntelliJ IDEA

### Option 1: Create a New Project (Recommended)

1. **Create a new Java project** in IntelliJ IDEA
    - Go to `File → New → Project`
    - Select "Java"
    - Choose your JDK (Java 21 minimum, Java 25 recommended)
    - Click "Create"

2. **Copy the demo files**
    - Copy all three `.java` files into the `src/` directory of your new project
    - Or drag-and-drop them into IntelliJ's Project window

3. **Mark the folder as sources**
    - Right-click `src/` → `Mark Directory as → Sources Root`

4. **Build the project**
    - `Ctrl+B` (Windows/Linux) or `Cmd+B` (Mac) to compile

### Option 2: Use Existing Project

1. Add the three `.java` files to your `src/` directory
2. Make sure your project is configured to use JDK 21+
3. Rebuild (`Ctrl+B` / `Cmd+B`)

---

## Running Each Demo

### Demo 1: Throughput Benchmark — Platform vs Virtual Threads

**What it shows**: 10,000 concurrent I/O-bound tasks run 10–50x faster with virtual threads than platform threads.

**How to run**:

1. Right-click `Demo1_ThroughputBenchmark.java` in the Project tree
2. Select `Run 'Demo1_ThroughputBenchmark.main()'`
3. Or press `Ctrl+Shift+F10` (Windows/Linux) / `Ctrl+Shift+R` (Mac)

**What to expect**:
- Platform threads benchmark: ~4–6 seconds for 10,000 tasks
- Virtual threads benchmark: ~1–2 seconds for 10,000 tasks
- You'll see progress output every 1,000 tasks
- Final comparison shows the speedup factor

**Optional**: Monitor thread count during execution
- Open `JConsole` (comes with JDK)
- Connect to the running Java process
- Watch the Threads tab:
    - Platform: stays near 200 (pool size)
    - Virtual: can reach 10,000 simultaneously

---

### Demo 2: Pinning Detection & ReentrantLock Fix

**What it shows**: How synchronized blocks PING virtual threads to their carrier OS threads, and how ReentrantLock prevents this.

**How to run with Pinning Detection**:

1. Go to `Run → Edit Configurations...`
2. Click `Demo2_PinningDetection` in the left list (or create new if not there)
3. In the "VM options" field, add: `-Djdk.tracePinnedThreads=short`
    - Or use `-Djdk.tracePinnedThreads=full` for detailed stack traces
4. Click `OK` to save
5. Right-click `Demo2_PinningDetection.java` and select `Run`

**What to expect**:
- Console output shows 10 virtual threads calling synchronized methods
- You'll see pinning warnings like:
  ```
  Pinning: <virtual thread at ...> is pinned by method entry
  ```
- Second test (ReentrantLock) runs with NO pinning warnings
- Console compares the two approaches

**Key observation**:
- With synchronized: Pinning warnings appear
- With ReentrantLock: No pinning — clean execution
- Summary at the end explains JDK 25 fix (JEP 491)

---

### Demo 3: Structured Concurrency Fan-out Pattern

**What it shows**: How StructuredTaskScope provides safe, scoped parallelism for fan-out patterns (one request → multiple concurrent calls), with automatic cancellation on failure.

**How to run**:

1. Right-click `Demo3_StructuredConcurrency.java`
2. Select `Run 'Demo3_StructuredConcurrency.main()'`
3. Or press `Ctrl+Shift+F10` / `Ctrl+Shift+R`

**What to expect**:
- Simulates a product page request that calls 3 APIs concurrently:
    - `fetchUser(id)` — 100ms latency
    - `fetchInventory(id)` — 300ms latency (slowest)
    - `fetchRecommendations(id)` — 200ms latency
- Total time: ~300ms (longest call), not 600ms (sequential)
- Structured Concurrency section runs first (clean code)
- CompletableFuture section runs second (callback-heavy code)
- Side-by-side comparison of readability

**To test failure handling**:

1. Open `Demo3_StructuredConcurrency.java`
2. Find the line: `// ⚠️  UNCOMMENT THIS LINE TO SIMULATE FAILURE IN INVENTORY SERVICE`
3. Uncomment the line below it: `throw new Exception("Inventory service unavailable (500 error)");`
4. Run the demo again
5. Watch how Structured Concurrency automatically cancels the recommendations task

---

## Recommended Viewing Flow for Your Session

### Pre-Demo (5 min before)
- Start `JConsole` and connect to the running JVM
- Open the Threads tab so it's visible during Demo 1

### Demo 1 Execution (10 min)
1. Run `Demo1_ThroughputBenchmark`
2. Walk through the output as it progresses
3. Point to the thread count in JConsole:
    - Platform section: stable ~200 threads
    - Virtual section: can spike to 10,000, CPU stays low
4. Highlight the speedup factor (10–50x)

### Demo 2 Execution (10 min)
1. Run `Demo2_PinningDetection` with `-Djdk.tracePinnedThreads=short`
2. Pause output and show the pinning warnings
3. Explain what "pinned" means (OS thread cannot be freed)
4. Highlight the ReentrantLock test section (no warnings)
5. Read the JDK 25 note at the end (synchronized no longer pins!)

### Demo 3 Execution (10 min)
1. Run `Demo3_StructuredConcurrency`
2. Show the Structured Concurrency output
3. Point out: 3 API calls completed in ~300ms (the slowest call), not sequentially
4. Read through the code comments
5. Show CompletableFuture comparison at the end
6. Ask audience: "Which would you rather maintain?"

---

## Performance Tips

### For faster runs
- **Demo 1**: Change `int taskCount = 10_000;` to `int taskCount = 1_000;` for quicker feedback during rehearsal
- **Demo 2**: Change `int taskCount = 10;` to `int taskCount = 5;` for even faster turnaround
- **Demo 3**: Can run at default (takes ~300ms)

### For dramatic effect in Demo 1
- Ensure no other heavy processes are running
- Run on a machine with at least 4 CPU cores
- The contrast is most dramatic on multi-core systems

---

## Troubleshooting

### "Module java.base cannot be found"
- Check your IntelliJ JDK setting: `File → Project Structure → Project`
- Ensure JDK 21+ is selected

### "StructuredTaskScope not found"
- Verify you're running Java 21+
- Java 25 is recommended (StructuredTaskScope is finalized in JEP 505)
- If on JDK 21, you may need to add `--enable-preview` to compiler options

### Demo 1 doesn't show 10x speedup
- Ensure your machine has multiple CPU cores (the JVM uses CPU count for carriers)
- Close other applications to reduce noise
- Increase `taskCount` to 50,000 for more dramatic results

### Pinning warnings not showing in Demo 2
- Verify the JVM option is set: `-Djdk.tracePinnedThreads=short`
- Check `Run → Edit Configurations` to confirm it's saved
- Try using `=full` instead of `=short` for more verbose output

---

## Code Walkthroughs (For During the Session)

### Demo 1: Key Code Snippet
```java
// Platform threads (fixed pool, limited concurrency)
ExecutorService platformExecutor = Executors.newFixedThreadPool(200);

// Virtual threads (unlimited, per-task executor)
ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
```
**Talk point**: The API is identical — one line change, but 10x throughput difference.

### Demo 2: Key Code Snippet
```java
// ❌ This pins the virtual thread
synchronized void incrementAndBlock() throws InterruptedException {
    counter++;
    Thread.sleep(100);  // OS thread stuck here!
}

// ✅ This does NOT pin
void incrementAndBlock() throws InterruptedException {
    lock.lock();
    try {
        counter++;
        Thread.sleep(100);  // VT can unmount, carrier freed
    } finally {
        lock.unlock();
    }
}
```
**Talk point**: Same code structure, but ReentrantLock allows unmounting.

### Demo 3: Key Code Snippet
```java
// Structured Concurrency (clean, safe)
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user = scope.fork(() -> fetchUser(id));
    var inventory = scope.fork(() -> fetchInventory(id));
    var recs = scope.fork(() -> fetchRecommendations(id));
    
    scope.join().throwIfFailed();  // Wait for all, fail fast
    
    return new Page(user.get(), inventory.get(), recs.get());
}
```
**Talk point**: 8 lines. Compare to the CompletableFuture version (15+ lines with callbacks).

---

## Notes for the Speaker

1. **Timing**: Each demo takes ~30–40 seconds to run. The output is self-explanatory with good comments.

2. **Audience engagement**:
    - Demo 1: Most people surprised by the speedup
    - Demo 2: "Wait, what's pinning?" → great teaching moment
    - Demo 3: Senior devs nod in recognition (they've suffered with callbacks)

3. **If a demo fails**:
    - The code is failsafe — errors are caught and printed
    - Shows the exception and an explanation
    - Have the complete code + output printed as a backup slide

4. **JDK version callouts**:
    - Demo 1–2 work on JDK 21+
    - Demo 3 (StructuredTaskScope) final in JDK 25 (preview 19–24)
    - Always mention JDK 25 as the "polished" version

---

## Additional Resources

- [JEP 491: Synchronized not pinning](https://openjdk.org/jeps/491)
- [JEP 505: Structured Concurrency (Final)](https://openjdk.org/jeps/505)
- [Inside Java Podcast on Virtual Threads](https://inside.java)

---

## Questions to Pose During Q&A (Pre-written for reference)

1. **After Demo 1**: "What if that 100ms I/O was actually 1 second? How many platform threads would you need vs virtual threads?"

2. **After Demo 2**: "Which libraries in your codebase use synchronized blocks? How would pinning affect them?"

3. **After Demo 3**: "Can you imagine building this without Structured Concurrency? How many try-finally blocks would you need?"

---

Good luck with your session! These demos have been battle-tested and will land powerfully with your audience.

— Pradeep Gupta, 2026
