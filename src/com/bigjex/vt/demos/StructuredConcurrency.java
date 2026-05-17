package com.bigjex.vt.demos;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;

/**
 * Demo 3: Structured Concurrency Fan-out Pattern (JEP 505)
 * 
 * This demo shows the power of StructuredTaskScope for safe, scoped parallelism.
 * It simulates a product page that needs to fan out 3 concurrent API calls:
 *   1. fetchUser(id)
 *   2. fetchInventory(id)
 *   3. fetchRecommendations(id)
 * 
 * Key feature: When ONE subtask fails, all siblings auto-cancel.
 * This is built-in scope management — no orphaned threads, no zombie tasks.
 * 
 * Compare the clean, readable code here with raw CompletableFuture chaining!
 * 
 * Execution:
 *   1. Run as-is — all three API calls succeed, page renders
 *   2. Uncomment the "// Simulate failure in inventory service" line
 *   3. Run again — watch how the failure propagates and siblings cancel
 *   4. Show the console output showing structured cancellation
 *   5. Contrast with CompletableFuture code at the bottom
 * 
 * Expected results:
 *   - Success case: All three results returned, page assembled in ~300ms (longest call)
 *   - Failure case: Exception caught, siblings auto-cancelled, no hanging tasks
 *   - Code readability: Synchronous-looking, no callback chains
 */
public class StructuredConcurrency {

    /**
     * Simulated API responses
     */
    static class UserDTO {
        public final String id;
        public final String name;
        public final String email;

        UserDTO(String id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        @Override
        public String toString() {
            return String.format("User{id=%s, name=%s, email=%s}", id, name, email);
        }
    }

    static class InventoryDTO {
        public final String productId;
        public final int stockCount;
        public final String warehouse;

        InventoryDTO(String productId, int stockCount, String warehouse) {
            this.productId = productId;
            this.stockCount = stockCount;
            this.warehouse = warehouse;
        }

        @Override
        public String toString() {
            return String.format("Inventory{productId=%s, stock=%d, warehouse=%s}", productId, stockCount, warehouse);
        }
    }

    static class RecommendationsDTO {
        public final String[] recommendedProductIds;
        public final String reason;

        RecommendationsDTO(String[] recommendedProductIds, String reason) {
            this.recommendedProductIds = recommendedProductIds;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return String.format("Recommendations{items=%d, reason=%s}", recommendedProductIds.length, reason);
        }
    }

    static class ProductPage {
        public final UserDTO user;
        public final InventoryDTO inventory;
        public final RecommendationsDTO recommendations;

        ProductPage(UserDTO user, InventoryDTO inventory, RecommendationsDTO recommendations) {
            this.user = user;
            this.inventory = inventory;
            this.recommendations = recommendations;
        }

        @Override
        public String toString() {
            return String.format(
                "ProductPage[\n  %s,\n  %s,\n  %s\n]",
                user, inventory, recommendations
            );
        }
    }

    /**
     * Simulated API calls with artificial latency
     */
    static class APIService {

        static UserDTO fetchUser(String userId) throws Exception {
            System.out.println("  → API: fetchUser(" + userId + ") starting (latency: 100ms)");
            Thread.sleep(100); // Simulate network latency
            System.out.println("  ✓ API: fetchUser(" + userId + ") complete");
            return new UserDTO(userId, "Pradeep Gupta", "pradeep@example.com");
        }

        static InventoryDTO fetchInventory(String productId) throws Exception {
            System.out.println("  → API: fetchInventory(" + productId + ") starting (latency: 300ms)");
            Thread.sleep(300); // Simulate longer network latency

            // ⚠️  UNCOMMENT THIS LINE TO SIMULATE FAILURE IN INVENTORY SERVICE
            // throw new Exception("Inventory service unavailable (500 error)");

            System.out.println("  ✓ API: fetchInventory(" + productId + ") complete");
            return new InventoryDTO(productId, 42, "Warehouse-East");
        }

        static RecommendationsDTO fetchRecommendations(String productId) throws Exception {
            System.out.println("  → API: fetchRecommendations(" + productId + ") starting (latency: 200ms)");
            Thread.sleep(200); // Simulate network latency
            System.out.println("  ✓ API: fetchRecommendations(" + productId + ") complete");
            return new RecommendationsDTO(
                new String[]{"PROD-002", "PROD-003", "PROD-005"},
                "Customers who viewed this also viewed"
            );
        }
    }

    /**
     * The beautiful pattern: Structured Concurrency with StructuredTaskScope.ShutdownOnFailure
     * 
     * This is what you write in JDK 19+ (preview in 19-21, final in 25 via JEP 505)
     */
    static ProductPage buildProductPageStructured(String userId, String productId) throws Exception {
        System.out.println("\n🎯 Building product page (Structured Concurrency approach)...\n");

        // ✨ This is the magic: a structured scope that manages task lifecycle
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Fork three API calls concurrently (virtual threads)
            // Each call gets its own virtual thread, but they're all scoped
            var userTask = scope.fork(() -> APIService.fetchUser(userId));
            var inventoryTask = scope.fork(() -> APIService.fetchInventory(productId));
            var recommendationsTask = scope.fork(() -> APIService.fetchRecommendations(productId));

            // Join all tasks — wait for completion or failure
            // ⚠️  If ANY task fails, ShutdownOnFailure cancels the others
            scope.join().throwIfFailed();

            // At this point, all three calls succeeded
            // Get results and assemble the page
            return new ProductPage(
                userTask.get(),
                inventoryTask.get(),
                recommendationsTask.get()
            );
        }
        // Scope auto-closes here — any still-running tasks are cancelled
    }

    /**
     * Compare with CompletableFuture (the old way)
     * 
     * This is callback-based, harder to read, and doesn't have automatic scope cleanup
     */
    static ProductPage buildProductPageCompletableFuture(String userId, String productId) throws Exception {
        System.out.println("\n📋 Building product page (CompletableFuture approach)...\n");

        // Three async calls wrapped in CompletableFuture
        CompletableFuture<UserDTO> userFuture =
            CompletableFuture.supplyAsync(() -> {
                try {
                    return APIService.fetchUser(userId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());

        CompletableFuture<InventoryDTO> inventoryFuture =
            CompletableFuture.supplyAsync(() -> {
                try {
                    return APIService.fetchInventory(productId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());

        CompletableFuture<RecommendationsDTO> recommendationsFuture =
            CompletableFuture.supplyAsync(() -> {
                try {
                    return APIService.fetchRecommendations(productId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());

        // Combine all three and map to ProductPage
        // This is hard to read and doesn't auto-cancel on failure
        return CompletableFuture.allOf(userFuture, inventoryFuture, recommendationsFuture)
            .thenApply(unused -> {
                try {
                    return new ProductPage(userFuture.get(), inventoryFuture.get(), recommendationsFuture.get());
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            })
            .get();
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("Demo 3: Structured Concurrency Fan-out Pattern (JEP 505)");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("Simulating a product page request that fans out 3 concurrent API calls:");
        System.out.println("  1. fetchUser(userId)");
        System.out.println("  2. fetchInventory(productId)  [slowest, 300ms]");
        System.out.println("  3. fetchRecommendations(productId)");
        System.out.println();

        String userId = "user-12345";
        String productId = "PROD-001";

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // TEST 1: Structured Concurrency (clean, safe, modern)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n" + "=".repeat(80));
        System.out.println("✨ APPROACH 1: Structured Concurrency (JEP 505)");
        System.out.println("=".repeat(80));

        try {
            long startTime = System.currentTimeMillis();
            ProductPage page = buildProductPageStructured(userId, productId);
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("\n✅ SUCCESS! Page assembled in " + duration + "ms");
            System.out.println("\nPage content:");
            System.out.println(page);

            System.out.println("\n💡 STRUCTURED CONCURRENCY ADVANTAGES:");
            System.out.println("  ✓ Synchronous-looking code (easy to read)");
            System.out.println("  ✓ Automatic scope management (auto-cancellation)");
            System.out.println("  ✓ If inventory fails, recommendations auto-cancel (no orphaned tasks)");
            System.out.println("  ✓ Structured error handling (all-or-nothing semantics)");

        } catch (Exception e) {
            System.out.println("\n❌ FAILURE CASE!");
            System.out.println("Exception: " + e.getMessage());
            System.out.println("\nKey point: When inventory failed, the scope auto-cancelled");
            System.out.println("the recommendations task. No zombie threads, no resource leaks.");
            System.out.println("\nThis is why senior developers love Structured Concurrency.");
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // TEST 2: CompletableFuture (harder to read, no auto-cancellation)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("📋 APPROACH 2: CompletableFuture (legacy approach)");
        System.out.println("=".repeat(80));

        try {
            long startTime = System.currentTimeMillis();
            ProductPage page = buildProductPageCompletableFuture(userId, productId);
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("\n✅ SUCCESS! Page assembled in " + duration + "ms");
            System.out.println("\nPage content:");
            System.out.println(page);

            System.out.println("\n⚠️  COMPLETABLEFUTURE DRAWBACKS:");
            System.out.println("  ✗ Callback-based (harder to follow control flow)");
            System.out.println("  ✗ Manual error handling required");
            System.out.println("  ✗ If one fails, you must manually cancel others (prone to forgetting)");
            System.out.println("  ✗ Debugging multi-level chains is painful");

        } catch (Exception e) {
            System.out.println("\n❌ FAILURE (notice the complexity)!");
            System.out.println("Exception: " + e.getMessage());
            System.out.println("\nWith CompletableFuture, you'd need to manually cancel remaining tasks.");
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // SUMMARY
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("STRUCTURED CONCURRENCY — THE SENIOR DEVELOPER'S PATTERN");
        System.out.println("=".repeat(80));

        System.out.println("\nWhy Structured Concurrency matters:");
        System.out.println("  1. Code looks synchronous (easier to reason about)");
        System.out.println("  2. All tasks in a scope share one lifecycle (born together, die together)");
        System.out.println("  3. On failure, siblings auto-cancel (no resource leaks)");
        System.out.println("  4. Debugging is straightforward (no callback spaghetti)");
        System.out.println("  5. Virtual threads + StructuredTaskScope = perfect pair for microservices");

        System.out.println("\nWhen to use Structured Concurrency:");
        System.out.println("  • Fan-out patterns (one request triggers multiple parallel calls)");
        System.out.println("  • Timeout-sensitive operations (scope.join(timeout) built-in)");
        System.out.println("  • Error propagation matters (all-or-nothing semantics)");
        System.out.println("  • Any scoped parallelism (your task group has clear boundaries)");

        System.out.println("\nCompare the code readability above.");
        System.out.println("Would YOU rather maintain the StructuredTaskScope version or the CF version?");
        System.out.println("This is why Java developers stopped reaching for CompletableFuture!");

        System.out.println("\n" + "=".repeat(80));
    }
}
