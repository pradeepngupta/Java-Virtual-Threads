Slide 1 — Opening (2–3 min)

"Welcome everyone! Today's session is on one of the most exciting features Java has delivered in the last two decades — Virtual Threads. 
And I want to be transparent with you: the slides you're looking at were prepared with AI assistance — specifically, Claude — 
and every live demo I'm going to run today was coded using AI assistance as well. 

I believe that's actually a fitting start, because one of the things you'll see today is how modern Java 
— Project Loom, Structured Concurrency — 
is designed for exactly the kind of high-concurrency, high-throughput systems that the AI era demands. So let's get into it."

Slide 2 — Introduction (2 min)

"Quick note on who I am — and yes, Claude pulled my GitHub avatar and dropped it right into the deck, which I think is pretty cool. 

I'm Pradeep Gupta, a technology leader with over 20 years of experience in large-scale systems. 
I started my career with Java 1.4, and I'm still using it in 2025 — which I think gives me a particular perspective on today's topic.

Now, one thing that gives this talk extra credibility: I recently attended the Bengaluru Java User Group conference. 
And one of my friends — someone I worked with in a previous organisation — presented this exact topic there. 
He's currently at Oracle, and he's a JDK Committer who actually committed code for Virtual Threads into the JDK. 
So when I say I've gone deep on this, I mean I've had a front-row seat to both the practitioner side and the implementer side of this feature."

Slide 3 — The Problem (8–10 min, including Demo 1)

"Let me tell you a story from 2005. 
I was a junior developer, and our team had just finished building a full IVR solution for a major telecom company
— think Airtel or Vodafone scale. 

We had tested it thoroughly with over 5,000 phone numbers. It was solid. We were confident. 
And then came the Customer Acceptance Test — the CAT. 

The customer handed us a list of 20,000 phone numbers and said, run the campaign. And our server crashed. The JVM went down.

We spent hours figuring out what went wrong. And eventually we traced it back to one thing: 
we had hit the OS thread limit on the server. 

Every phone call was being handled by one OS thread. 
At 5,000 calls, we were fine. At 20,000, the OS simply couldn't create any more threads. The JVM ran out of headroom.

As a junior dev, I remember asking — why can't Java just handle more? Why is there a hard ceiling? 
And the answer at the time was: that's just how it works. One thread per request. That's the model.

Fast forward to 2009 — Go introduced goroutines, which solved exactly this problem by decoupling concurrency from OS threads. 
But Java? Nothing. Java developers who wanted this kind of scalability had to turn to reactive programming 
— CompletableFuture, Project Reactor, RxJava — frameworks that work, but fundamentally change how you write and reason about code.

And now, in 2025, I can say with genuine pride: Java has solved this. Properly. With Virtual Threads. 
And the beautiful thing is, you don't need to rewrite your code.
Let me show you exactly what I mean. Let's run a quick demo."

Slide 4 — Platform vs Virtual (3–4 min)

"I'm going to skim this slide quickly, 
but let me jump to the paint and draw this out — because I think seeing it drawn is more intuitive than reading a table."

Slide 5 — Scheduler Architecture (4–5 min)

"Now here's what Virtual Threads do with that idle time. 
Back to the paint — when a Virtual Thread hits a blocking call, the JVM unmounts it from the OS thread. The OS thread is now free. 
The JVM's scheduler — and I want to name it specifically: the ForkJoinPool — 
picks up another Virtual Thread and mounts it on that same OS thread. The OS thread never sits idle.

The ForkJoinPool was introduced in Java 7, and it's been battle-tested for over a decade. 
It's a work-stealing scheduler, which means threads actively go looking for work when they run out. 
That's a deliberate, proven choice by the Project Loom team — don't reinvent the scheduler, use the one that's already trusted."

Slide 6 — Mount, Unmount & Pinning (8–10 min, including Demo 2)

"Now let me show you the mounting and unmounting actually happening in real time."

After Demo 2:

"In JDK 25, this is fixed at the JVM level. Synchronized blocks no longer pin Virtual Threads. 
That's JEP 491, and it means that legacy codebases get this benefit for free the moment they upgrade. 
That's one of the most compelling upgrade arguments for JDK 25."

Slide 7 — When NOT to Use VT (3–4 min)

"Now, every tool has its limits. Let me quickly walk through when you should not reach for Virtual Threads
— because this is the nuance that separates someone who read the docs from someone who's actually deployed this."

Cover the four anti-patterns briskly: 
CPU-bound tasks (no I/O to unmount on), 
ThreadLocal abuse (memory explosion at scale), 
synchronized-heavy legacy code (will pin), and 
reactive stacks (migration complexity with marginal gain). 

Spend 30–45 seconds on each. Then skim the JDK 25 features as a positive close to the slide.

Slide 8 — Use Cases & API (2 min)

"We've already seen the API in action in the demos, so I'll just anchor the three patterns visually — 
Thread.ofVirtual() for a single thread, 
newVirtualThreadPerTaskExecutor() as your drop-in thread pool replacement, and 
StructuredTaskScope for fan-out patterns. 

These three cover 90% of adoption scenarios."

Slide 9 — Live Demos Summary (1 min)

"We've done Demo 1 and Demo 2. 
Demo 3 — the Structured Concurrency fan-out — I'm going to do right now, 
because it's the most powerful pattern for anyone building microservices."

Slide 10–12 — Closing Sequence (5 min)

"Before we open for Q&A, I want to share a couple of things quickly."

Go to Slide 11 — spend 90 seconds on your upcoming work, book, newsletter. 
Then to Slide 12 — put the survey up, mention it takes 2 minutes, ask everyone to scan. Then:

"While that's up on screen — let's open the floor. What questions do you have?"
