Q&A Simulation — Java Virtual Threads Session
Audience Questions + Speaker Answers

How to read this document
Each question is tagged by the audience persona most likely to ask it. The answers are written in your voice — conversational, authoritative, honest, and drawing from your real experience where applicable.

🟢 JUNIOR DEVELOPER QUESTIONS

Q1. "You kept saying virtual threads are 'lightweight' — but what exactly does that mean? Where do they actually live?"
[Junior Dev — probably first time hearing this]

"Great question — and it's the right place to start.
A platform thread — what Java has always had — is essentially a thin Java wrapper around an OS thread. The OS thread comes with a fixed stack, typically 1 megabyte on most systems. That's memory committed upfront, whether your thread is actively doing work or sitting idle. The OS manages it, the OS schedules it, and there's a hard limit on how many the OS will create before saying no.
A virtual thread, on the other hand, lives on the Java heap. Its stack is stored as a regular Java object — called a continuation — that starts at around 2 kilobytes and grows dynamically only as needed. It has no OS thread behind it by default. The JVM creates and destroys virtual threads the same way it creates and destroys any object — fast, cheap, and garbage collected when done.
So when I say lightweight, I mean: cheap to create, cheap in memory, managed by the JVM rather than the OS. The difference between a platform thread and a virtual thread is roughly the difference between owning a car and taking a taxi. The car is always yours — parked, consuming space, whether you're driving or not. The taxi appears when you need it and disappears when you're done.
That's why you can have millions of virtual threads. You simply couldn't do that with platform threads — the OS wouldn't allow it."


Q2. "In Demo 1, you changed one line — the executor. But does the rest of my code just work? Do I need to change anything else?"
[Junior Dev — practical, wants to know if it's safe to try]

"Largely yes — and that was a deliberate design goal of Project Loom from day one.
Virtual threads implement java.lang.Thread. The API is identical. If your code creates threads, submits tasks to an executor, or uses Thread.sleep() for blocking — it all works. The JVM handles the rest beneath the surface.
The two things you need to watch for — and we covered both today:
First, pinning. If your code or any library your code calls uses synchronized blocks with blocking operations inside, you may get pinned threads on JDK 21-24. Run with -Djdk.tracePinnedThreads=full first, find the hot spots, and either replace them with ReentrantLock or upgrade to JDK 25 where this is fixed.
Second, ThreadLocal. If you're using frameworks that store heavyweight per-request objects in ThreadLocal — and many popular frameworks do — you may see memory pressure because you now have far more virtual threads than you ever had platform threads. This is something to monitor rather than panic about — but it's worth being aware of.
My honest advice to a junior developer: try it on a side project or a non-critical service first. The adoption path is gentler than almost any other Java feature I've seen in 20 years. But always validate with your own workload before rolling to production."


Q3. "What happens if I create a million virtual threads? Does the JVM actually create a million OS threads behind the scenes?"
[Junior Dev — still building the mental model]

"No — and this is the core insight of the entire talk.
The number of carrier OS threads — the real OS threads that actually run on CPU — is by default equal to the number of CPU cores on your machine. If you have a 16-core server, you have 16 carrier threads. That's it.
The million virtual threads are objects on the heap. They take turns being mounted onto those 16 carrier threads. When a virtual thread is running, it's mounted. When it blocks — on I/O, on a lock, on sleep — it unmounts, and the next virtual thread in the scheduler queue gets mounted onto that carrier.
Think of it like a stage with 16 spotlights — that's your carrier threads. You have a million actors — that's your virtual threads. Only 16 actors are ever on stage at once. But because each actor's scene is very short before they step off for a moment — waiting for a prop, waiting for a cue — the 16 spotlights are constantly illuminating different actors, and the audience sees rich, continuous activity.
The JVM's ForkJoinPool scheduler is the stage manager doing all of this coordination invisibly."


Q4. "Can I use virtual threads with Spring Boot? Or is this only for raw Java?"
[Junior Dev — works in a framework, hasn't seen this in the wild]

"Yes — and Spring Boot actually made this beautifully simple. From Spring Boot 3.2 onwards, enabling virtual threads is a single property in your application.properties:
spring.threads.virtual.enabled=true
That's it. Spring Boot replaces its internal thread pool with a virtual thread executor. Your controllers, your service layer, your repository calls — all handled by virtual threads automatically.
For Tomcat specifically — which is the default embedded server in Spring Boot — each incoming HTTP request is now handled by a virtual thread rather than a platform thread from the Tomcat thread pool. Your code doesn't change. Your business logic doesn't change. Your JDBC calls, your HTTP client calls — all continue to work.
I've seen teams enable this in existing Spring Boot applications and immediately see throughput improvements without a single line of application code changed. That's the promise delivered.
The caveat — always the same one — is to run your pinning detection first, especially if you're pulling in older third-party libraries."


🔵 SENIOR DEVELOPER QUESTIONS

Q5. "You mentioned LockSupport.park() is why ReentrantLock doesn't pin. But what about other blocking operations — like reading from a socket, or reading a file? Do those pin?"
[Senior Dev — has read the JEP, wants the precise mechanics]

"Excellent — this gets into the heart of how Project Loom actually works under the hood.
The JDK team rewrote the core I/O operations to be virtual-thread-aware. When a virtual thread calls a blocking socket read — SocketInputStream.read() — the JVM intercepts that call. Instead of blocking the carrier OS thread, it registers the file descriptor with an I/O poller, unmounts the virtual thread, and parks it. When the data arrives, the poller wakes the virtual thread and it gets remounted.
So standard Java I/O operations — java.net, java.io, JDBC over standard drivers, HttpClient — are all handled safely. The JVM team specifically retrofitted these.
Where it gets complicated is native code. If you call into JNI, or if a library makes native OS calls directly bypassing the JVM's I/O layer — those will still pin. The JVM cannot intercept native operations. That's the fundamental boundary.
And this is exactly the point I made with the IVR story. Our dialer calls were native — they went straight to the telecom stack via JNI. Virtual threads would have helped the Java-level concurrency, but those native dialer calls would still have pinned carrier threads.
The practical rule: if it's in java.* or javax.*, it's almost certainly been made VT-safe. If it goes through JNI or native libraries, assume it pins until proven otherwise."


Q6. "You showed StructuredTaskScope.ShutdownOnFailure. What if I want to return the first result that succeeds and cancel the rest? Like a race between services?"
[Senior Dev — building microservices, wants the full API]

"Great — that's the second policy, and it's the mirror image of what we demoed.
StructuredTaskScope.ShutdownOnSuccess — it shuts down the scope the moment any one subtask succeeds. The others are cancelled.
The pattern looks like this:
javatry (var scope = new StructuredTaskScope.ShutdownOnSuccess<Response>()) {
scope.fork(() -> callPrimaryService());
scope.fork(() -> callFallbackService());
scope.fork(() -> callSecondaryFallback());

    scope.join();
    return scope.result();  // first successful result wins
}
This is the hedged request pattern — you fire at multiple backends simultaneously and take whichever responds first. The latency of the operation is the latency of the fastest responder, not any individual service's SLA.
What makes this beautiful with Structured Concurrency is that cancellation is guaranteed. You don't write a finally block to clean up the other calls. The scope handles it. The slow services are interrupted the moment the winner returns.
With CompletableFuture, implementing this correctly — especially the cancellation — requires careful manual wiring. With StructuredTaskScope, it's the default behaviour."


Q7. "I work on a high-frequency trading system. Latency is microseconds. Should I be looking at virtual threads?"
[Senior Dev / Tech Lead — performance-critical domain]

"Honest answer — no, not for your hot path.
Virtual threads are optimised for throughput at scale under I/O blocking. The scheduling overhead — mounting, unmounting, ForkJoinPool task dispatch — introduces non-deterministic latency. For microsecond-sensitive operations, that jitter is unacceptable.
High-frequency trading systems typically want the opposite of what virtual threads offer: dedicated, pinned, CPU-bound threads on isolated cores, with controlled context-switch behaviour and predictable memory access patterns. That's platform threads with thread affinity, often combined with busy-spinning rather than blocking at all.
Where virtual threads might benefit your ecosystem is on the operational boundary — order management systems, risk monitoring, reporting pipelines — the systems adjacent to the hot path that deal with high-concurrency I/O but aren't in the microsecond critical path.
The general principle: the closer you are to hardware and the more latency-sensitive you are, the less virtual threads help you. The further you are from hardware and the more you're waiting on network, disk, or remote services, the more they help."


Q8. "What happens to thread-local storage? We use InheritableThreadLocal heavily for passing context — tracing IDs, security context, that kind of thing."
[Senior Dev — has seen this bite teams in production]

"This is one of the most practically important questions in virtual thread adoption, and I'm glad you raised it.
ThreadLocal and InheritableThreadLocal technically work with virtual threads. The values are stored per-thread, and since each virtual thread is a thread, they work as expected mechanically.
The problems are:
First, memory. If you're storing heavyweight objects in ThreadLocal — database connections, caches, large context objects — and you now have 100,000 virtual threads instead of 500 platform threads, you've multiplied your per-thread memory by 200x. That's a potential memory explosion.
Second, pooling semantics. Some frameworks assume ThreadLocal values persist because threads are reused from a pool. Virtual threads are not pooled — you create one per task and discard it. ThreadLocal values created in one VT don't carry over to the next task. Frameworks that rely on this can behave incorrectly.
The right solution going forward is Scoped Values — JEP 506, finalized in JDK 25. Scoped Values are explicitly designed for virtual threads. They're read-only within a scope, inherited safely by child threads in a structured task scope, and don't suffer the memory explosion problem because they're not per-thread storage — they're per-scope storage.
For tracing IDs and security context specifically — Micrometer and Spring's request context are both being updated to use Scoped Values. If you're on older versions, run with pinning detection first, then look at what's in your ThreadLocals and decide what needs migrating.
Short-term: it works. Long-term: migrate to Scoped Values."


Q9. "JDBC is still blocking under the hood, right? How does virtual thread unmounting actually work with database connections?"
[Senior Dev — backend engineer who has seen connection pool hell]

"Yes — JDBC is still fundamentally synchronous and blocking. And virtual threads handle it better than you might expect — but not magically.
Here's what happens. Your virtual thread calls connection.executeQuery(). Beneath the surface, that goes to a socket read waiting for the database to respond. Because the JVM has retrofitted the socket layer to be virtual-thread-aware, the virtual thread unmounts while waiting for the database response. The carrier thread is freed. Other virtual threads run. When the database sends its response, your virtual thread gets remounted and continues.
So the waiting on the network is handled efficiently. That's the win.
What's not changed: the connection pool. You still have a finite number of database connections. If all 50 connections in your pool are in use, your virtual thread blocks waiting to acquire a connection. That wait does unmount the VT — so it's not a carrier thread problem — but it is a database connection problem.
The practical implication: virtual threads don't let you remove connection pools. They do let you be more aggressive about concurrent requests because the waiting time between acquiring the connection and getting the result no longer consumes OS threads. Your connection pool size is still tuned by database capacity, not by server thread limits.
Some teams see this and try to dramatically increase connection pool sizes. That's the wrong response — you'll hammer your database. The right response is to right-size your pool based on DB capacity and enjoy the fact that your server is no longer the bottleneck."


🟡 TECH LEAD QUESTIONS

Q10. "We have a large microservices codebase on JDK 17. What's the migration strategy? Where do we start?"
[Tech Lead — owns the roadmap, needs a plan not theory]

"I'll give you a four-stage migration strategy that I think is practical and low-risk.
Stage 1: Upgrade to JDK 21 first. Don't try to jump to 25 in one step if you're on 17. JDK 21 is LTS, virtual threads are stable there, and most frameworks have been validated against it. Get your build, your CI, and your libraries working on 21. This is groundwork, not feature adoption.
Stage 2: Run pinning detection in your staging environment. Before you change a single line of production code, add -Djdk.tracePinnedThreads=full to your staging JVM flags and run your full load test suite. You'll get a list of pinning sites — probably mostly in third-party libraries. Evaluate each one: is it on a hot path? Is there a newer version of the library that fixes it?
Stage 3: Enable virtual threads on your most I/O-heavy, stateless services first. In Spring Boot 3.2+, that's one property. Pick a service that does a lot of downstream HTTP calls or database queries and has a clear throughput metric. Enable it. Load test. Measure. The results will make your business case for the rest of the migration.
Stage 4: Upgrade to JDK 25 and simplify. JEP 491 removes the synchronized pinning concern. JEP 506 gives you Scoped Values for context propagation. Most of the caveats we discussed today go away or become much smaller. JDK 25 is where the full promise is delivered.
The biggest risk is not technical — it's teams enabling virtual threads without the pinning detection step and then wondering why their latency got worse. Always instrument first."


Q11. "How do virtual threads interact with reactive frameworks like Project Reactor or Vert.x? Are they redundant now?"
[Tech Lead — probably made the bet on reactive 3 years ago, wondering if it was a mistake]

"They're not redundant — and I want to be careful about how I frame this, because I know a lot of teams made significant investments in reactive.
Reactive frameworks solved a real problem: how do you achieve high concurrency without blocking OS threads? Virtual threads solve the same problem, but at a different layer — at the JVM and platform level rather than at the programming model level.
The honest comparison:
Reactive code is efficient but cognitively expensive. Debugging a Reactor pipeline with back-pressure, flatMap, error handling, and context propagation across thread hops is genuinely difficult. The stack traces are terrible. The mental model requires expertise to maintain safely.
Virtual thread code is efficient and cognitively cheap. It looks like synchronous code. Stack traces make sense. You can read it top to bottom like you always could.
For new services being written today — I would reach for virtual threads over reactive unless you have a specific reason to need the reactive programming model.
For existing reactive services — I would not migrate them immediately. A working reactive service is not broken. The migration has cost and risk. Evaluate when you have a clear performance or maintainability problem that virtual threads would solve.
Vert.x has actually made a thoughtful decision here — they support virtual threads alongside their event loop model, letting you write virtual-thread-based code that integrates with the Vert.x ecosystem. That's the pragmatic path.
The framing I'd push back on is 'were we wrong to go reactive?' You weren't wrong. You solved a hard problem with the best tools available. The tools just got better."


Q12. "You mentioned Netty uses Java Reflection to customise the Virtual Thread scheduler. Doesn't that break with newer JDK versions? Isn't that fragile?"
[Tech Lead — detail-oriented, thinks about long-term maintainability]

"You are absolutely right to flag it as fragile — and I think Netty would agree with you.
Using Reflection to access internal JVM APIs is always a bet against time. It works today because the internal structure of the ForkJoinPool scheduler hasn't changed in a way that breaks their access. But it's not guaranteed by any public contract. A JDK update can break it silently, and the JVM's module system — --illegal-access flags, strong encapsulation — makes this increasingly risky with each release.
The reason Netty went down this path is not because it's elegant — it's because there was no alternative. The JDK intentionally exposed no public API for scheduler configuration. For a framework with Netty's specific I/O event loop requirements, the default ForkJoinPool behaviour wasn't optimal, and reflection was the only door available.
The right long-term answer — and this is an open conversation in the Java community — is for the JDK to expose a stable, public scheduler API for virtual threads. That would let frameworks like Netty configure scheduling behaviour without relying on internal reflective access.
Until that happens, Netty's approach is the pragmatic one. But it's a footgun, and they know it. They track JDK changes carefully because of it.
For the rest of us — this is not something you should replicate in your application code. The ForkJoinPool default is the right choice for 99.9% of workloads. Leave the scheduler alone."


Q13. "How do I monitor and observe virtual threads in production? My current APM tools — Dynatrace, Datadog — will they see them?"
[Tech Lead — owns production observability, practical concern]

"This is an area that's actively maturing, and I'll be honest about where the gaps still are.
JVM built-in tooling works well. JFR — Java Flight Recorder — supports virtual threads natively. You can track virtual thread creation, pinning events, scheduler queue depth, and carrier thread utilisation. JFR is the most reliable observability tool for virtual threads today, and it's built into the JDK at zero cost.
JConsole and VisualVM show virtual threads as threads — you'll see them in the threads tab — but at millions of VTs, the UI becomes impractical. What you actually want to monitor is carrier thread utilisation and scheduler queue depth, not individual VT lifecycle.
APM tools — Dynatrace, Datadog, New Relic — have been updating their Java agents to handle virtual threads, but with varying quality. The main challenge is distributed tracing context propagation. Traditional agents instrument Thread class or ThreadLocal for trace context. With virtual threads changing thread identity during execution, some agents lose the context between the fork and the continuation.
As of late 2024, Datadog and Dynatrace both have beta or GA support for virtual thread tracing. I would test your specific APM agent version in a staging environment and look specifically at whether trace context propagates correctly across blocking calls. That's the canary — if a distributed trace is complete end-to-end through a virtual thread's mount/unmount cycle, your agent is working correctly.
For metrics — throughput, latency histograms, error rates — these are thread-agnostic and will be unaffected."


🔴 MANAGER / LEADERSHIP QUESTIONS

Q14. "Should I tell my team to start adopting virtual threads now? Or wait for things to mature more?"
[Engineering Manager — risk-conscious, owns the team's roadmap]

"My recommendation is to start now, but start deliberately.
Virtual threads are not experimental. They became stable in JDK 21 in September 2023 — that's a production-ready LTS release. JDK 25 delivers the full picture. The technology is mature. The risk of not evaluating it is that you fall behind teams who are already extracting throughput improvements without hardware cost increases.
'Start deliberately' means:
First, get your team on JDK 21 or 25. Even without using virtual threads, this gives you access to all the other modern Java features and positions you to adopt incrementally.
Second, identify one service — ideally I/O-heavy, stateless, with clear throughput metrics — and run a proof of concept. In Spring Boot 3.2+, enabling virtual threads is one property. Measure the before and after. Let the data make the case internally.
Third, don't mandate a big-bang migration. Let the proof of concept results guide the rollout pace.
The cost of waiting is that your competitors are already doing this. The cost of moving recklessly is production incidents. The path between them is one well-instrumented proof of concept."


Q15. "What's the business case? How do I explain this to my CTO or VP of Engineering?"
[Manager — needs to translate technology into cost and value]

"Three business arguments, in order of impact:
First: Infrastructure cost reduction. Today, to handle 10,000 concurrent users, your servers need enough memory and OS threads to support that concurrency. With virtual threads, the same hardware handles significantly more concurrent connections — our Demo 1 showed 10x throughput improvement on the same machine. Before you provision more servers or move to larger instance types, test whether virtual threads close the gap. Cloud compute costs money every month. A 30% reduction in required instances is a real dollar number.
Second: Developer productivity. Reactive programming — the previous answer to high-concurrency Java — is expensive to write, expensive to debug, and expensive to hire for. It requires specialists. Virtual threads let your entire team write plain synchronous Java and still achieve the same throughput. Onboarding new developers is faster. Debugging production issues is faster. The talent pool is wider.
Third: Technical debt reduction. A lot of teams are sitting on complex reactive codebases that were built to solve a problem that now has a simpler solution. Over time, those codebases are expensive to maintain. Virtual threads give you a migration path to simpler code without sacrificing performance.
If I were making this case to a CTO, I'd frame it simply: Virtual Threads let us serve more users with the same hardware, written by the same developers, without changing our programming model. Run the proof of concept. Let the infrastructure bill tell the story."


Q16. "Is this a Java-only thing? How does it compare to what Go and Kotlin do?"
[Manager / Tech Lead — evaluating technology strategy, multi-language context]

"Good question — and worth answering precisely because the comparison gets fuzzy in casual discussions.
Go Goroutines — this is what I referenced in the 2005 story. Go solved this in 2009, when Java was still telling developers to deal with OS thread limits. Goroutines are Go's equivalent of virtual threads — lightweight, multiplexed over OS threads, with cooperative scheduling. Go had a 14-year head start on this specific problem. Java's advantage is the ecosystem — billions of lines of existing Java code now benefit from virtual threads without rewriting.
Kotlin Coroutines — Kotlin's approach is different in an important way. Coroutines are a programming model — they're syntactic and library-level, built on top of platform threads. They require you to write suspend functions, use async/await, and understand the coroutine scope model. They're powerful, but they change how you write code. Virtual threads don't. Virtual threads work with existing blocking Java code transparently.
If you have a Kotlin codebase, coroutines are still the native Kotlin idiom. But for Java code, virtual threads are more transparent — you don't need to propagate suspend annotations throughout your entire codebase.
The honest summary: Go was first. Kotlin coroutines require code changes. Java virtual threads require the least change for the most existing code. If you're evaluating a greenfield project across languages, Go and Kotlin are both excellent. If you have an existing Java investment, virtual threads are the most practical path to high concurrency."


🔁 MIXED / ADVANCED QUESTIONS

Q17. "You said virtual threads are not pooled and should be created per task. But connection pools still exist. Doesn't that create a mismatch?"
[Senior Dev / Tech Lead — thinking about the architecture carefully]

"This is a really insightful observation — there is a mismatch, and it's intentional.
Virtual threads are compute resources — they represent the execution of a task. They're cheap enough that creating one per task is correct. Pooling them would add synchronization overhead that destroys the benefit.
Database connections are external resources — they represent a physical connection to a database server. The database has limits — connection limits, lock contention limits, buffer limits. A connection is not cheap to create, and you can't create a million of them because the database would fall over. Pooling them is correct.
So the design is:

Virtual thread per task — created, used, garbage collected. No pool.
Connection pool — sized based on database capacity, not server thread capacity.

The important shift is: you no longer need to inflate your thread pool to match your connection pool. Previously, teams would size their thread pool to match their connection pool, because threads blocked waiting for connections. With virtual threads, a thread blocked waiting for a connection is just a parked virtual thread — cheap, not consuming a carrier. So your thread equivalent count can be much larger than your connection count without OS thread exhaustion.
The practical result: you can serve far more concurrent requests than you have database connections, because most requests are waiting for I/O rather than holding connections."


Q18. "You mentioned Scoped Values replace ThreadLocal. But we have thousands of lines of code using ThreadLocal. Is there a migration path or are we just stuck?"
[Senior Dev — pragmatic, inheriting legacy code]

"You're not stuck — but you're right that it's not a find-and-replace migration. Let me give you the practical path.
First: not all ThreadLocal usage is problematic. ThreadLocal of lightweight values — a thread-bound random number generator, a simple request ID string — is fine with virtual threads. The problem is specifically heavyweight objects stored per-thread in frameworks that weren't designed for millions of threads.
Second: identify what you actually have. Run your application under load with virtual threads enabled and watch memory. If memory is stable, your ThreadLocal usage is fine. If you see memory growing unexpectedly, that's your signal to investigate what's in the ThreadLocals.
Third: the migration to Scoped Values is gradual. Scoped Values are specifically designed to carry immutable context — trace IDs, security principals, request-scoped configuration — through a tree of tasks including forked virtual threads. The migration pattern is:
Old: ThreadLocal<RequestContext> ctx = new ThreadLocal<>();
New: ScopedValue<RequestContext> ctx = ScopedValue.newInstance();
Used with: ScopedValue.where(ctx, value).run(() -> { ... });
Fourth: if you can't migrate yet, it works as-is. ThreadLocal is not removed. It's not deprecated. It works with virtual threads. The migration to Scoped Values is a best practice for new code and for code you're actively maintaining, not a hard requirement. Pragmatically — focus on the ThreadLocals in your hot paths first, and treat the rest as technical debt to address incrementally."


Q19. "If I start a virtual thread and it throws an uncaught exception — what happens? How is error handling different?"
[Junior to Mid Dev — wants to understand operational behaviour]

"Behaviorally it's the same as a platform thread — the exception propagates to the thread's uncaught exception handler, and if none is set, the JVM's default handler prints the stack trace.
But in practice, how you use virtual threads changes where you handle errors, and this is actually one of the places Structured Concurrency makes a big difference.
With raw virtual threads — Thread.ofVirtual().start(task) — if that task throws, it goes to the uncaught exception handler. The calling code doesn't see it unless you explicitly set up a handler or use a Future to collect the result.
With ExecutorService.submit() — which returns a Future — the exception is captured inside the Future. You see it when you call future.get(), which throws ExecutionException wrapping your original exception.
With StructuredTaskScope — and this is the elegant part — if any forked task throws, scope.join().throwIfFailed() re-throws it on the calling thread. The exception propagates naturally through the call stack exactly as if you'd written synchronous code. You catch it in a normal try-catch. No wrapping, no unwrapping, no special handling.
This is one of the underappreciated benefits of Structured Concurrency — it makes error handling in concurrent code feel like error handling in sequential code. The cognitive overhead drops dramatically."


Q20. "Final question — you've been writing Java for 20 years and you've seen many 'revolutionary' features that didn't live up to the hype. Is Virtual Threads genuinely different? Or is this another EJB?"
[Senior Dev / Tech Lead — earned their cynicism honestly]

"(laughs)
Fair. Very fair. I've lived through EJBs. I've lived through XML-driven everything. I've lived through the promise that annotations would solve all our configuration problems. Cynicism is earned in this industry.
Here's why I think Virtual Threads is genuinely different — and I'll give you three reasons, not marketing.
First: it solves a problem we've had for 25 years. This isn't a new abstraction looking for a problem. The OS thread limitation has been the ceiling on Java server throughput since Java 1.0. We've been building workarounds — thread pools, reactive frameworks, async callbacks — for two decades. Virtual threads remove the ceiling entirely. That's solving a known, painful, expensive problem.
Second: it requires no programming model change. EJBs required you to reorganise your entire application around their model. Reactive required you to learn a new way of writing code and hire people who understood it. Virtual threads work with the blocking, synchronous Java you've always written. You change the executor. That's it. The risk of adoption is the lowest I've seen for any major Java feature.
Third: the ecosystem is already there. Spring Boot, Quarkus, Micronaut, Helidon — all support virtual threads. JDK 25 finalizes the companion APIs. The JVM team has been working on this for nearly a decade. This is not a preview feature with an uncertain future. It's in the LTS release.
Is it going to solve every problem? No — and we covered that today. CPU-bound workloads, native calls, heavy ThreadLocal usage — these are still real constraints. But for the category of problem it addresses — high-concurrency I/O-bound Java services — it's the most impactful change to the JVM since generics, maybe since lambda.
My 2005 self — who watched that IVR server crash with 20,000 phone numbers — would have found it hard to believe Java would get here. But here we are.
It took 20 years. And it was worth the wait."


Summary Cheat-Sheet for Q&A
If asked about...Your anchor point
How lightweight means ... Heap objects, 2KB stack, GC collected
One-line migration ... newVirtualThreadPerTaskExecutor()
Pinning deep cause ... Native ObjectMonitor vs Java LockSupport
Native call pinning ... Yes, still pins — IVR story
JDBC / connection pools ... Pools still needed, sized by DB not threads
Spring Boot ... One property, 3.2+
ThreadLocal / context ... Works, but migrate to Scoped Values
APM / observability ... JFR is best, APM agents improving
Go vs Kotlin vs Java ... Go first, Kotlin changes model, Java transparent
Business case ... Infra cost, dev productivity, tech debt
Reactive redundancy ... Not redundant, different tradeoff
ForkJoinPool / Netty ... No public API, reflection is fragile footgun
Is it real this time ... Yes — known problem, no model change, ecosystem ready

You are ready for any question this room can throw at you. 🎤