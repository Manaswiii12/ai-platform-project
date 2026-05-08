package com.aiplatform.backend.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import com.aiplatform.backend.service.GeminiService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class TestController {

    private final GeminiService geminiService;

    // ─── Metrics Tracking ────────────────────────────────────────────
    public static AtomicInteger totalRequests   = new AtomicInteger(0);
    public static AtomicInteger successRequests = new AtomicInteger(0);
    public static AtomicInteger failedRequests  = new AtomicInteger(0);
    public static AtomicInteger repairCount     = new AtomicInteger(0);
    public static List<Long>    latencies       = new ArrayList<>();

    public TestController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    // ─── Clean string for safe JSON embedding ────────────────────────
    private String clean(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "")
                  .replace("\n", " ")
                  .replace("\r", " ")
                  .replace("\"", "'")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    // ─── Check if response is valid JSON ─────────────────────────────
    private boolean isValidJson(String response) {
        if (response == null || response.isBlank()) return false;
        if (response.contains("\"error\"")) return false;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        return start != -1 && end != -1 && end > start;
    }

    // ─── Extract JSON block from response ────────────────────────────
    private String extractJson(String response) {
        if (response == null) return "{}";
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    // ─── Validate and Auto-Repair ────────────────────────────────────
    private String validateAndRepair(String response, String originalPrompt, String stageName) {
        if (isValidJson(response)) {
            return extractJson(response);
        }

        repairCount.incrementAndGet();
        System.out.println("[REPAIR] " + stageName + " failed — retrying...");

        String repairPrompt = """
                The previous response was invalid JSON. Please try again.
                Task: %s
                IMPORTANT: Return ONLY valid JSON. No explanation. No markdown. No extra text.
                Start your response with { and end with }
                """.formatted(originalPrompt);

        String repaired = geminiService.generateContent(repairPrompt);

        if (isValidJson(repaired)) {
            System.out.println("[REPAIR] " + stageName + " repaired successfully.");
            return extractJson(repaired);
        }

        System.out.println("[REPAIR] " + stageName + " repair failed — using fallback.");
        return "{\"status\": \"generation_failed\", \"stage\": \"" + stageName + "\", \"reason\": \"Could not generate valid JSON after repair attempt\"}";
    }

    // ─── Vague Prompt Check ──────────────────────────────────────────
    private boolean isTooVague(String prompt) {
        String[] words = prompt.trim().split("\\s+");
        return words.length < 4;
    }

    // ─── Metrics Endpoint ────────────────────────────────────────────
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        int total   = totalRequests.get();
        int success = successRequests.get();
        double avgLatency = latencies.isEmpty() ? 0 :
            latencies.stream().mapToLong(l -> l).average().orElse(0);

        metrics.put("totalRequests",      total);
        metrics.put("successfulRequests", success);
        metrics.put("failedRequests",     failedRequests.get());
        metrics.put("successRate",        total == 0 ? "0%" : (success * 100 / total) + "%");
        metrics.put("autoRepairs",        repairCount.get());
        metrics.put("avgLatencyMs",       Math.round(avgLatency));
        return metrics;
    }

    // ─── Main Pipeline ───────────────────────────────────────────────
    @PostMapping("/generate")
    public Map<String, Object> generateApp(@RequestBody String prompt) {

        totalRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();

        String cleanPrompt = prompt.trim().replaceAll("^\"|\"$", "");
        Map<String, Object> result = new LinkedHashMap<>();

        // ── Vague Prompt Check ───────────────────────────────────────
        if (isTooVague(cleanPrompt)) {
            failedRequests.incrementAndGet();
            result.put("error", "Prompt is too vague.");
            result.put("suggestion", "Please describe your app in more detail.");
            result.put("example", "Build a CRM with login, contacts, dashboard, role-based access and payments.");
            return result;
        }

        result.put("assumptions", "Auth enabled by default. Admin role always included. REST API assumed.");

        // ── Stage 1: Intent Extraction ───────────────────────────────
        String intentPrompt = """
                You are an expert system analyst.
                Extract structured intent from this user request: "%s"
                Return ONLY valid JSON with this exact structure, no extra text:
                {
                  "appName": "",
                  "appType": "",
                  "entities": [],
                  "features": [],
                  "roles": [],
                  "hasPayments": false,
                  "hasAuth": true
                }
                """.formatted(cleanPrompt);

        String intent = validateAndRepair(
            geminiService.generateContent(intentPrompt), intentPrompt, "Stage1-Intent");
        result.put("stage1_intent", intent);

        // ── Stage 2: System Design ───────────────────────────────────
        String designPrompt = """
                You are a software architect.
                Based on this app intent: "%s"
                Design the system architecture.
                Return ONLY valid JSON with this exact structure, no extra text:
                {
                  "pages": [],
                  "flows": [],
                  "authModel": {},
                  "integrations": []
                }
                """.formatted(clean(intent));

        String design = validateAndRepair(
            geminiService.generateContent(designPrompt), designPrompt, "Stage2-Design");
        result.put("stage2_design", design);

        // ── Stage 3: Schema Generation ───────────────────────────────
        String schemaPrompt = """
                You are a backend engineer.
                Based on this system design: "%s"
                Generate complete schemas.
                Return ONLY valid JSON with this exact structure, no extra text:
                {
                  "uiSchema": { "pages": [], "components": [] },
                  "apiSchema": { "endpoints": [] },
                  "dbSchema":  { "tables": [] },
                  "authRules": { "roles": [], "permissions": [] }
                }
                """.formatted(clean(design));

        String schema = validateAndRepair(
            geminiService.generateContent(schemaPrompt), schemaPrompt, "Stage3-Schema");
        result.put("stage3_schema", schema);

        // ── Stage 4: Validation + Repair ─────────────────────────────
        String validatePrompt = """
                You are a code reviewer and schema validator.
                Validate this schema for cross-layer consistency: "%s"
                Check ALL of these:
                - Every API endpoint has a matching DB table
                - Every UI page has a matching API endpoint
                - Every role in authRules has permissions defined
                - No hallucinated or missing fields
                Fix ALL issues and return ONLY the corrected valid JSON.
                No explanation. Start with { and end with }
                """.formatted(clean(schema));

        String validated = validateAndRepair(
            geminiService.generateContent(validatePrompt), validatePrompt, "Stage4-Validation");
        result.put("stage4_validated", validated);

        // ── Stage 5: Runtime Simulation ──────────────────────────────
        String runtimePrompt = """
                You are a runtime engine simulator.
                Given this validated app schema: "%s"
                Simulate execution of the app and return ONLY valid JSON:
                {
                  "simulatedRoutes": [],
                  "simulatedPages": [],
                  "simulatedDbTables": [],
                  "executionStatus": "success",
                  "warnings": []
                }
                """.formatted(clean(validated));

        String runtime = validateAndRepair(
            geminiService.generateContent(runtimePrompt), runtimePrompt, "Stage5-Runtime");
        result.put("stage5_runtime_simulation", runtime);

        // ── Metrics ──────────────────────────────────────────────────
        long latency = System.currentTimeMillis() - startTime;
        latencies.add(latency);
        successRequests.incrementAndGet();

        result.put("meta", Map.of(
            "latencyMs",   latency,
            "autoRepairs", repairCount.get(),
            "model",       "llama-3.3-70b-versatile",
            "pipeline",    "5-stage"
        ));

        return result;
    }
}