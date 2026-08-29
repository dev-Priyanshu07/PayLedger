import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merchant-side load generator for the PayG API.
 *
 * <p>Run without building anything:
 * <pre>
 *   java tools/LoadGen.java
 *   java tools/LoadGen.java --requests 500 --concurrency 50
 * </pre>
 *
 * <p>Two jobs. It measures throughput and latency under concurrent load, and
 * it asserts the guarantees the API claims - most importantly that firing the
 * same idempotency key from many threads at once still yields exactly one
 * payment. That last one is the only check that can tell an insert-first
 * design apart from a check-then-insert one; both pass a sequential test.
 *
 * <p>Exits non-zero if any assertion fails, so it can gate a build.
 */
public class LoadGen {

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    static String baseUrl = "http://localhost:8080";
    static String apiKey = "sk_test_merchant_a";
    static int requests = 200;
    static int concurrency = 25;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** Set by any failing assertion; drives the exit code. */
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        parseArgs(args);

        System.out.printf("PayG load generator -> %s  (%d requests, %d concurrent)%n%n",
                baseUrl, requests, concurrency);

        if (!reachable()) {
            System.err.println("Cannot reach " + baseUrl + " - is the app running?");
            System.exit(2);
        }

        steadyLoad();
        concurrentSameIdempotencyKey();
        concurrentSameOrder();
        rejectionPaths();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("All checks passed.");
        } else {
            System.out.println("FAILED (" + failures.size() + "):");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // Scenario 1 - steady load
    //
    // The normal merchant flow, run concurrently: declare an order, then
    // charge it. Establishes the throughput and latency baseline.
    // ------------------------------------------------------------------

    static void steadyLoad() throws Exception {
        System.out.println("[1] Steady load: order + payment per iteration");

        List<Long> orderLatencies = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Long> paymentLatencies = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();

        long started = System.nanoTime();

        try (ExecutorService pool = boundedPool()) {
            for (int i = 0; i < requests; i++) {
                pool.submit(() -> {
                    String ref = UUID.randomUUID().toString();

                    Response order = createOrder(ref, 50_000);
                    orderLatencies.add(order.nanos());
                    count(statuses, order.status());
                    if (order.status() != 201) return;

                    Response payment = createPayment(idOf(order), "idem_" + ref, "cust_" + ref);
                    paymentLatencies.add(payment.nanos());
                    count(statuses, payment.status());
                });
            }
        }

        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        int total = orderLatencies.size() + paymentLatencies.size();

        System.out.printf("    %d requests in %.2fs  (%.0f req/s)%n", total, seconds, total / seconds);
        System.out.println("    statuses: " + render(statuses));
        report("    POST /v1/orders  ", orderLatencies);
        report("    POST /v1/payments", paymentLatencies);

        int created = statuses.getOrDefault(201, new AtomicInteger()).get();
        int accepted = statuses.getOrDefault(202, new AtomicInteger()).get();
        check(created == requests, "expected " + requests + " orders created, got " + created);
        check(accepted == requests, "expected " + requests + " payments accepted, got " + accepted);
    }

    // ------------------------------------------------------------------
    // Scenario 2 - the one that matters
    //
    // Many threads, one idempotency key, released simultaneously. A
    // check-then-insert implementation passes a sequential replay test and
    // fails this one: every thread sees "no payment yet" and every thread
    // inserts. Exactly one distinct id is the whole claim of N2.
    // ------------------------------------------------------------------

    static void concurrentSameIdempotencyKey() throws Exception {
        System.out.println("\n[2] Idempotency race: " + concurrency + " threads, one key, fired together");

        Response order = createOrder(UUID.randomUUID().toString(), 12_345);
        check(order.status() == 201, "setup order failed: " + order.status());
        if (order.status() != 201) return;

        String key = "race_" + UUID.randomUUID();
        Map<String, AtomicInteger> ids = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();

        fireTogether(concurrency, () -> {
            Response r = createPayment(idOf(order), key, "cust_race");
            count(statuses, r.status());
            String id = idOf(r);
            if (id != null) ids.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        });

        System.out.println("    statuses: " + render(statuses));
        System.out.println("    distinct payment ids: " + ids.size() + " " + ids.keySet());

        check(ids.size() == 1, "expected exactly 1 payment id under concurrent replay, got " + ids.size());
        check(statuses.getOrDefault(202, new AtomicInteger()).get() == concurrency,
                "expected every concurrent replay to return 202, got " + render(statuses));
    }

    // ------------------------------------------------------------------
    // Scenario 3 - the same race on the orders side
    // ------------------------------------------------------------------

    static void concurrentSameOrder() throws Exception {
        System.out.println("\n[3] Order race: " + concurrency + " threads, one merchantOrderId");

        String ref = "race_order_" + UUID.randomUUID();
        Map<String, AtomicInteger> ids = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();

        fireTogether(concurrency, () -> {
            Response r = createOrder(ref, 777);
            count(statuses, r.status());
            String id = idOf(r);
            if (id != null) ids.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        });

        System.out.println("    statuses: " + render(statuses) + "  (one 201, rest 200)");
        System.out.println("    distinct order ids: " + ids.size());

        check(ids.size() == 1, "expected exactly 1 order id, got " + ids.size());
        check(statuses.getOrDefault(201, new AtomicInteger()).get() == 1,
                "expected exactly one 201, got " + render(statuses));
    }

    // ------------------------------------------------------------------
    // Scenario 4 - the API should refuse these
    // ------------------------------------------------------------------

    static void rejectionPaths() {
        System.out.println("\n[4] Rejection paths");

        Response order = createOrder(UUID.randomUUID().toString(), 999);
        String orderId = idOf(order);
        String key = "reuse_" + UUID.randomUUID();
        createPayment(orderId, key, "cust_original");

        expect("no api key",              post("/v1/orders", null, null, "{}"), 401);
        expect("bad api key",             post("/v1/orders", "nope", null, "{}"), 401);
        expect("negative amount",         createOrder(UUID.randomUUID().toString(), -1), 400);
        expect("missing idempotency key", post("/v1/payments", apiKey, null,
                                              payload(orderId, "c")), 400);
        expect("unknown order",           createPayment("00000000-0000-0000-0000-000000000000",
                                              "k_" + UUID.randomUUID(), "c"), 404);
        expect("key reused, diff body",   createPayment(orderId, key, "cust_CHANGED"), 409);
        expect("other merchant's order",  post("/v1/orders/" + orderId, "sk_test_merchant_b", null, null), 404);
    }

    static void expect(String label, Response actual, int wanted) {
        boolean ok = actual.status() == wanted;
        System.out.printf("    %-24s %d %s%n", label, actual.status(), ok ? "" : "<- expected " + wanted);
        check(ok, label + ": expected " + wanted + ", got " + actual.status());
    }

    // ------------------------------------------------------------------
    // API calls
    // ------------------------------------------------------------------

    static Response createOrder(String merchantOrderId, long amountMinor) {
        String body = """
                {"merchantOrderId":"%s","amountMinor":%d,"currency":"INR"}"""
                .formatted(merchantOrderId, amountMinor);
        return post("/v1/orders", apiKey, null, body);
    }

    static Response createPayment(String orderId, String idempotencyKey, String customerRef) {
        return post("/v1/payments", apiKey, idempotencyKey, payload(orderId, customerRef));
    }

    static String payload(String orderId, String customerRef) {
        return """
                {"orderId":"%s","customerRef":"%s"}""".formatted(orderId, customerRef);
    }

    /** A null body means GET. A null apiKey means send no auth header. */
    static Response post(String path, String apiKey, String idempotencyKey, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");

        if (apiKey != null) b.header("X-API-Key", apiKey);
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body)); else b.GET();

        long started = System.nanoTime();
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), System.nanoTime() - started);
        } catch (Exception e) {
            return new Response(-1, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.nanoTime() - started);
        }
    }

    record Response(int status, String body, long nanos) {
    }

    static String idOf(Response r) {
        if (r.body() == null) return null;
        Matcher m = ID.matcher(r.body());
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * Releases every thread at the same instant. Without the latch the threads
     * start staggered by however long it takes to spawn them, which is long
     * enough for the first insert to commit - and then the race never happens
     * and the test proves nothing.
     */
    static void fireTogether(int threads, Runnable task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            done.await(60, TimeUnit.SECONDS);
        }
    }

    static ExecutorService boundedPool() {
        return Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory());
    }

    static void report(String label, List<Long> nanos) {
        if (nanos.isEmpty()) {
            System.out.println(label + "  no samples");
            return;
        }
        long[] ms = nanos.stream().mapToLong(n -> n / 1_000_000).sorted().toArray();
        System.out.printf("%s  n=%-5d p50=%dms  p95=%dms  p99=%dms  max=%dms%n",
                label, ms.length, pct(ms, 50), pct(ms, 95), pct(ms, 99), ms[ms.length - 1]);
    }

    static long pct(long[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    static void count(Map<Integer, AtomicInteger> counts, int status) {
        counts.computeIfAbsent(status, k -> new AtomicInteger()).incrementAndGet();
    }

    static String render(Map<Integer, AtomicInteger> counts) {
        return new java.util.TreeMap<>(counts).entrySet().stream()
                .map(e -> e.getKey() + "x" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    static void check(boolean condition, String message) {
        if (!condition) failures.add(message);
    }

    static boolean reachable() {
        return post("/v1/orders", null, null, "{}").status() > 0;
    }

    static void parseArgs(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        baseUrl = a.getOrDefault("base-url", baseUrl);
        apiKey = a.getOrDefault("api-key", apiKey);
        requests = Integer.parseInt(a.getOrDefault("requests", String.valueOf(requests)));
        concurrency = Integer.parseInt(a.getOrDefault("concurrency", String.valueOf(concurrency)));
        if (a.containsKey("help") || Arrays.asList(args).contains("--help")) {
            System.out.println("--base-url URL  --api-key KEY  --requests N  --concurrency N");
            System.exit(0);
        }
    }
}
