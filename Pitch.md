
Java Virtual Threads — Complete Speaker Script
Pradeep Gupta · Java Community Session

SLIDE 1 — Title Slide
⏱ 2–3 minutes

"Welcome everyone to today's Java Community session!
Today, I'm going to talk about one of the most significant features Java has delivered in the last two decades — Virtual Threads.
And before I dive in, I want to be completely transparent with you about two things.
First — the slides you're looking at right now? Claude prepared them for me. The live demos I'm going to run today? 
Coded using AI assistance. 
I think that's actually a fitting disclosure, because what you'll see today is 
how modern Java is designed for exactly the kind of high-concurrency, high-throughput systems that the AI era demands. 
Java and AI — not competitors, but collaborators.
Second — this is not a topic I picked from a documentation page. This is a topic I have lived. 
For 20 years. And you'll understand why in about 3 minutes.
Let's start."


SLIDE 2 — About the Speaker
⏱ 2 minutes

"Quick note on who I am — and yes, that's actually my GitHub avatar that Claude pulled and dropped right into the deck. 
I thought that was pretty impressive.
I'm Pradeep Gupta. Technology leader, 20+ years designing and operating large-scale software systems.
The last 12 years at JP Morgan. And 8+ years before that across the industry — enterprise software, early application servers, 
cloud-native platforms, and everything in between.
I'm also the author of Breakpoints of a Career — available on Amazon Kindle — and 
I write the Beyond the Stack newsletter on Substack and LinkedIn, where I explore software architecture, system design, 
and the long-term consequences of engineering decisions.

Now — one thing that gives today's session extra credibility beyond my own experience. 
I recently attended the Bengaluru Java User Group conference.
And one of my closest friends — someone I worked with in a previous organisation — presented this exact topic there. 
He is currently at Oracle. He is a JDK Committer. And he has personally committed code for Virtual Threads into the JDK.

So when I say I've gone deep on this topic, 
I mean I've had a front-row seat to both the practitioner side and the implementer side of this feature.
Alright. Let me tell you a story."


SLIDE 3 — Why Virtual Threads? The Scalability Crisis
⏱ 10 minutes including Demo 1

"The year is 2005. I'm a junior developer. 
Our team has just finished building a full IVR solution — Interactive Voice Response — for a major telecom company. 
Think Airtel or Vodafone scale. We spent months on this. We built it right. 
We tested it thoroughly — 5,000 phone numbers. It worked perfectly. We were confident. We were proud.

And then came the Customer Acceptance Test — the CAT.
The customer handed us a list of 20,000 phone numbers and said — run the campaign.
Our server crashed.
The JVM went down.

We spent hours in the post-mortem tracing the root cause. And eventually we found it: 
we had hit the OS thread limit on the server. Every phone call was being handled by one OS thread. 
At 5,000 calls, we were fine. At 20,000 — the OS simply could not create any more threads. The JVM ran out of headroom.

As a junior developer, I remember asking my senior: 'Why can't Java just handle more? Why is there a hard ceiling?'
And the answer was: 'That's just how it works. One thread per request. That's the model. Deal with it.'
(pause)
That answer never sat right with me.

Fast forward to 2009 — Go programming language introduces goroutines. Lightweight concurrency, multiplexed over OS threads. 
Go solved this problem on day one. But Java? Nothing. 
Java developers who needed this scale had to turn to reactive programming — CompletableFuture, Project Reactor, RxJava — 
frameworks that work, but that fundamentally change how you write and reason about your code. 
Your code stops looking like Java and starts looking like callback spaghetti.

And now, in 2025, I can say with genuine pride: Java has solved this. Properly. Elegantly. Without asking you to rewrite your code.
That's Virtual Threads.

(Beat. Let that land.)

Now look at the three problems on this slide — OS thread cost, blocking I/O waste, and throughput ceiling. 
These are the three walls that the Java threading model has been hitting for two decades. 
Virtual threads tear all three walls down simultaneously.
Let me prove it. Right now. Live."

▶ RUN DEMO 1 — Throughput Benchmark

"I'm going to run 10,000 concurrent I/O-bound tasks — each one blocking for 100 milliseconds, 
simulating a real DB call or network request.

First, with a fixed platform thread pool of 200 threads. Watch the time.
(run benchmark — platform threads)
You see that? About 5 seconds for 10,000 tasks.

Now I'm going to make one change — literally one line — switching to Executors.newVirtualThreadPerTaskExecutor(). 
Same tasks. Same 100ms I/O. Same machine.
(run benchmark — virtual threads)

One to two seconds. That's a 10x improvement from one line of code.

And if you open JConsole right now, you'll see something fascinating. 
Platform thread run: ~200 threads. 
Virtual thread run: the JVM is managing thousands of virtual threads, 
but the carrier OS thread count stays near the number of CPU cores on this machine. 

The JVM is doing extraordinary work beneath the surface.
That is the promise of Project Loom."


SLIDE 4 — Platform Threads vs Virtual Threads — Side by Side
⏱ 3–4 minutes

"Let me give you the comparison table briefly — I want you to see the contrast — and then 
I'm going to switch to the whiteboard, because this is one concept that is much clearer when drawn than when read.

The critical rows here: 
backing mechanism — platform threads are 1:1 with OS kernel threads. 
Virtual threads are M:N — millions of VTs managed on a handful of carrier OS threads. 

Creation cost — platform thread is approximately 1 megabyte of stack, slow to create. 
Virtual thread starts at around 2 kilobytes on the heap, created in microseconds. 

Blocking I/O — platform thread blocks the OS thread entirely. Virtual thread unmounts, freeing the carrier for another VT.

And the most important point at the bottom: 

Virtual Threads implement java.lang.Thread. The API is identical. You don't rewrite your application. 
You change the executor. That's it.

(switch to whiteboard/paint)
Let me draw you the picture of why blocking I/O is so costly — and why virtual threads solve it structurally."

→ Draw on whiteboard:

A single OS thread making a DB call
The DB call takes 100ms
OS thread sitting completely idle for 100ms
At 200 threads, 100ms wait = throughput bottleneck
Label it: "CPU is idle. Memory is consumed. Nothing is happening."


"This idle time — this wasted 100 milliseconds per thread per request — multiplied by thousands of concurrent users — 
is exactly where your server capacity goes to die. 
This is the IVR crash in a diagram. And this is the problem that Virtual Threads were built to eliminate."


SLIDE 5 — Under the Hood — Scheduler Architecture
⏱ 4–5 minutes

"Now here's what the JVM does differently with Virtual Threads. Back to the whiteboard — let me extend that diagram.
(draw the unmount/remount sequence)

When a Virtual Thread hits a blocking call — a DB query, a network read, a file operation — the JVM unmounts it. 
It saves the virtual thread's stack as a plain Java object on the heap — a few kilobytes. 
The carrier OS thread is now completely free. 
The JVM's scheduler immediately mounts the next virtual thread onto that same carrier. The OS thread never sits idle.

That carrier thread never stops working. 
It's like a barista who serves the next customer the moment the espresso machine is brewing — 
not standing around waiting for coffee to drip.
(back to slide)

The component doing this orchestration is the ForkJoinPool — 
and I want to name it specifically because the choice was deliberate and important.

ForkJoinPool was introduced in Java 7. It has been battle-tested in production systems for 15+ years. 
It's a work-stealing scheduler — when one carrier thread runs out of tasks, it actively steals work from other carrier threads' queues. 
That's an aggressive, efficient scheduling strategy, and 
it's exactly what you need when you have millions of short-lived, I/O-interrupted tasks.

The Project Loom team made a very conscious engineering decision: don't reinvent the scheduler. Use the one that's already trusted.

Now — one question that comes up in advanced discussions: 'Can I change the scheduler? Can I plug in my own?'

The honest answer is: the JDK exposes no public API to change the Virtual Thread scheduler. Deliberately. 
The ForkJoinPool covers 99.9% of use cases perfectly for virtual threads. There was no need to expose that complexity publicly.
And that's the reason I call Java, A Simple language.

But what about that 0.1%? What about the edge cases where you might want a different scheduling strategy?
There is already a use case - Netty has different scheduling requirements. Their network I/O model is unique. 
And the Netty team customised the virtual thread scheduler using Java Reflection API to reach the internal configuration. 
They bypassed the lack of public API via reflection and tuned the scheduler to their needs.

That's a power-user move — not something most of us will ever need to do. 
But it tells you something important: the machinery is there. The ForkJoinPool is just the default choice, not a hard ceiling."


SLIDE 6 — Mount, Unmount & Pinning — The Critical Lifecycle
⏱ 9–10 minutes including Demo 2

"Let's walk through the full lifecycle of a Virtual Thread — left side of this slide. 
Six stages. Created, Mounted, Running, Unmounted, Re-mounted, Terminated.

The key moments are Mounted and Unmounted. 
When the VT hits a blocking operation, the scheduler saves its stack — literally copies a few kilobytes onto the heap — and 
frees the carrier. When the I/O completes, the VT gets re-mounted on any available carrier thread. 
Not necessarily the same one it left. It doesn't matter — all the state is in the VT's heap-allocated stack.
(switch to whiteboard/paint)

Do anyone see a potential problem in this lifecycle?
Lock - Let's keep it aside, I'll speak in a few seconds on it.

Native Call - Remembers Java 1.4 JNI or RMI? 
When a thread enters native code, the JVM loses visibility into it. 
It can't safely unmount it. It has to pin it to the carrier thread until it returns.

Now comes to the Lock part - And that's something the most dangerous trap in Virtual Threads.
Something that silently destroys your performance if you don't know about it.
Thread Pinning.

Imagine this scenario. A Virtual Thread is running. 
It enters a synchronized block — a lock inherited from 15-year-old library code. 
Inside that synchronized block, it hits a blocking call — a sleep, a DB wait, something. The JVM now needs to unmount the VT.
But It can't.
(draw the pinned scenario on whiteboard)

Why? Because synchronized in Java uses what's called an Object Monitor — implemented in native C++ code inside the JVM. 
The moment your thread enters that native ObjectMonitor, the JVM loses safe visibility into that thread. 
Native code might be inspecting the thread's stack. Native code might be storing pointers to thread-local data. 
The JVM cannot safely unmount a thread that native code might be touching.
So it pins it.
The virtual thread stays bolted to its carrier OS thread for the duration of that synchronized block.
The carrier can't run anything else. 
You've turned your virtual thread back into a platform thread — with all the cost and none of the benefit.

You can find pinning in your existing codebase immediately. Add this JVM flag: -Djdk.tracePinnedThreads=full. 
Run your application. Any time a virtual thread pins, you'll see exactly where in the output.

How to fix it? The answer is simple and the fix is available in two ways:
a) don't use synchronized blocks in hot paths. Then what is alternative? Re-Entrant Locks.
They are implemented in pure Java. They don't have the same native visibility problem.
The rule of thumb: native boundary = potential pin. Pure Java = safe to unmount.

ReentrantLock is different because it's implemented entirely in Java — specifically in AbstractQueuedSynchronizer. When a virtual thread can't acquire a ReentrantLock, it calls LockSupport.park(). And that is a JVM intrinsic — a pattern the JVM specifically recognises and knows how to handle. It unmounts the VT, parks it, frees the carrier. No pinning.
The rule of thumb: native boundary = potential pin. Pure Java = safe to unmount.
(back to slide — point to detect flag)

Let me show you."

▶ RUN DEMO 2 — Pinning Detection

"(Run with -Djdk.tracePinnedThreads=short)
Watch the output. Here — you see those 'Pinning:' warnings? That's the JVM telling you: 'This virtual thread is stuck. It cannot unmount. Your carrier thread is occupied.'
Now I'll switch to ReentrantLock and run again.
(run ReentrantLock version)
Clean. No pinning warnings at all. The virtual threads are freely mounting and unmounting.

That's one way to do it. Change your production code to use ReentrantLock instead of synchronized.
But the more exciting news is that the JDK 25 team has already fixed this for you.

(point to JEP 491 callout)
And this is the single most important reason to upgrade:
JEP 491. Synchronized no longer pins virtual threads. By default.
The JDK 25 team rewrote the monitor implementation to be virtual-thread-aware. 
synchronized now works like ReentrantLock under the hood — it uses the same unmount/remount mechanism.
Your legacy code — the code you inherited, the library code you can't modify — gets this fix for free the moment you upgrade to JDK 25.
That is an extraordinary gift to the Java ecosystem."

→ Now the important caveat:

"But I need to be honest with you — because this brings me back to my 2005 IVR story.
JEP 491 fixes the pinning caused by synchronized blocks. 
It does not fix pinning caused by native method calls — by JNI, by C library calls, by OS-level operations.
Think about what that IVR system was doing. 
Every phone call involved a native call to the telecom dialer to physically dial the number. 
That native call would still pin a virtual thread. 

So if we had virtual threads in 2005, and we had replaced our thread pool with virtual threads, 
we would have seen improvement — but the native dialer calls would still pin the carrier threads.

The lesson is subtle and important: 
Virtual Threads are not a silver bullet for every concurrency problem. 
They're a silver bullet for Java-level I/O blocking. Native boundaries are still boundaries.
If your code hits native calls on hot paths — you need to architect around that separately."


SLIDE 7 — When Virtual Threads Are NOT the Answer
⏱ 3–4 minutes

"Which brings me to the slide I think is actually the most valuable one in this deck — 
because the most dangerous thing a community can do is oversell a technology.

Four cases where virtual threads will not help you.

CPU-bound tasks. Number crunching, image processing, ML inference, video encoding. 
Virtual threads unmount on blocking operations — on waiting. 
If your thread is never waiting, it never unmounts. You get zero benefit. 
For CPU-bound work, your friends are platform threads, ForkJoinPool's parallel streams, and structured parallelism. Not virtual threads.

ThreadLocal abuse. This is a subtle one. 
Many frameworks — some famous ones — store heavyweight per-request objects in ThreadLocal. 
That design made sense when threads were scarce and expensive. 
With virtual threads, you might have a million VTs — each one carrying a ThreadLocal object. 
That's not a small overhead anymore. That's a memory explosion. 
Libraries are actively migrating to Scoped Values — JEP 506 in JDK 25 — which is the right pattern for per-VT data.

Synchronized-heavy legacy code on JDK 21-24. Yes, JDK 25 fixes this. 
But if you're on JDK 21, and your hot path goes through library code riddled with synchronized blocks, you need to audit first. 
Don't flip the executor switch and wonder why performance didn't improve.

Existing reactive or async stacks. 
If you're already on Project Reactor, CompletableFuture pipelines, or RxJava — migration has complexity cost. 
It's worth evaluating carefully. Virtual threads shine brightest on synchronous-looking, blocking I/O code. 
If you've already paid the cognitive price of reactive — the return on migration might be smaller than you expect.

Right side of this slide shows the JDK 25 improvements — 
JEP 491 synchronized fix, JEP 505 Structured Concurrency final, J
EP 506 Scoped Values final. 
These three together make JDK 25 the definitive release for virtual thread adoption.

And the anti-pattern I'll leave you with: 
do not pool virtual threads. I've seen this in code reviews. 
People create a virtual thread pool because that's what they've always done. 
Virtual threads are cheap. Creating them is microseconds. 
Pooling them adds contention and destroys the benefit. Create, use, discard. Let the GC collect them."


SLIDE 8 — Ideal Use Cases & Adopting Virtual Threads Today
⏱ 2 minutes

"We've already seen the API in action through the demos, so I'll anchor the three adoption patterns quickly.

The three use cases where virtual threads pay off most: 
high-throughput HTTP services — REST, gRPC, any request-per-thread server. 
Each request gets its own virtual thread. Code looks synchronous. Scales like async. 

Database-heavy workloads — JDBC queries, connection waits. 
You're not changing the connection pool — you're changing the thread that waits for results. 

And microservice fan-out — calling multiple upstreams per request. 
This is where Structured Concurrency — which we'll see in a moment — becomes the perfect pairing.

Three API patterns — all you need for 90% of adoption:

Thread.ofVirtual() — create a single named virtual thread.

Executors.newVirtualThreadPerTaskExecutor() — drop-in replacement for your cached thread pool.

StructuredTaskScope.ShutdownOnFailure() — scoped, safe, auto-cancelling fan-out.

The API is already in your hands. The question is when you choose to use it."


SLIDE 9 — Live Demo 3 — Structured Concurrency Fan-out
⏱ 6–7 minutes

"We've done two demos. Demo 1 showed the throughput gain. Demo 2 showed the pinning trap. 
This third demo is the one senior developers have been waiting for their entire careers.

Here's the scenario. 
A product page request fans out to three services simultaneously: fetchUser(), fetchInventory(), and fetchRecommendations(). 
Each has its own latency. Sequentially, that's 600 milliseconds. In parallel, it should be the slowest one — 300 milliseconds.

The question is: how do you write that in a way that's safe, readable, and self-cleaning?

Here's the Structured Concurrency answer — and I want you to read this code."

▶ RUN DEMO 3 — Structured Concurrency

"(Show the StructuredTaskScope code)
try (var scope = new StructuredTaskScope.ShutdownOnFailure())
One scope. Three forks. scope.join().throwIfFailed(). Get your results. Return the page.

That's eight lines of logic. No callbacks. No chaining. No manually cancelling siblings. 
The code reads exactly like synchronous code — but it's running in parallel.
(run the demo — success case)

300 milliseconds. All three results. Page assembled.

Now let me show you the failure case. I'm going to uncomment one line that throws an exception inside fetchInventory().
(run the failure case)
Watch what happens. Inventory throws. The scope catches it. The recommendations task is automatically cancelled. 
No orphaned thread waiting for a result nobody needs. No zombie tasks consuming resources. 
The scope manages the lifecycle of everything inside it.

(show the CompletableFuture version)
This is the equivalent with CompletableFuture. Count the lines. Count the try-catch blocks. Count the .thenApply() chains. 
And ask yourself — when this fails at 2 AM in production, which version would you rather debug?

This is why Structured Concurrency exists. 
Not to replace virtual threads — but to give virtual threads a programming model that matches how humans actually think about parallel work."


SLIDE 10 — Q&A
⏱ Opens Q&A, stays up during questions

"Before we open the floor, let me run through the next two slides quickly — I have some things to share with you —
(navigate to Slide 11)"


SLIDE 11 — What's Coming Next
⏱ 3–4 minutes

"Two things I want to share with you.
The first is personal.
I've been working on a book — Buzzing Java. 

I'm targeting a Q3 or Q4 launch this year. 
The manuscript is ready, and I'm currently looking for the right publisher — so if anyone here knows a publisher, 
please find me after the session.

The book is not a tutorial. It's not a framework guide. 
It's a conceptual and strategic book built around Java's original eleven buzzwords — the design promises Java made in 1995. 
Simple. Portable. Secure. Robust. Multithreaded. Dynamic. And so on. 
The book revisits each of those eleven promises and shows 
how they evolved — sometimes in ways even Java's creators didn't anticipate — into the engineering principles that now power 
cloud-native and AI-driven systems.

If you're an engineer who wants to understand why Java has survived 30 years — not just how to use it — this book is for you.

The second thing is BIGJEX Community. 
Code Review Dojo — June 2026. This is a session format I'm particularly excited about.
You bring code that's heading for public review. We review it live, in front of the community. 
We find the core problem together. We refactor it using GitHub Copilot — on stage, in real time. 
You walk away with the refactored code and a set of review patterns you can apply immediately.

Watch for the announcement in the next 1-2 weeks.

And for Q3 and Q4, we have two more live demo sessions planned — Spring AI and Spring Kafka. 
If you want to contribute a session or give a talk, please fill out the survey and mention your topic. 
This community runs on people like the ones in this room.

(navigate to Slide 12)"


SLIDE 12 — Survey & Close
⏱ Survey stays visible through all of Q&A

"Before questions — two minutes. Scan the QR code and fill out the feedback survey.
One thing I want to be upfront about: this survey is not anonymous. 
So please be honest, but know that I will read every response personally.

What I'm looking for from you: what landed, what didn't, what you want to go deeper on next time, 
and what this community should cover in the sessions ahead.
(QR slide stays visible)

Now — the floor is open. What questions do you have?"
