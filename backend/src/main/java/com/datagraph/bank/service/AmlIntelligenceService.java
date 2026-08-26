package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AmlIntelligenceService {
    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Path workerRoot;
    private final String pythonCommand;
    private final Duration timeout;

    public AmlIntelligenceService(
            ObjectMapper objectMapper,
            @Value("${worker.aml-intelligence.root:workers/aml-intelligence}") String workerRoot,
            @Value("${worker.aml-intelligence.python:}") String pythonCommand,
            @Value("${worker.python:python3}") String fallbackPythonCommand,
            @Value("${worker.aml-intelligence.timeout-seconds:180}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.workerRoot = resolveWorkerRoot(workerRoot);
        this.pythonCommand = WorkerPythonEnvironment.resolve(
                pythonCommand, this.workerRoot, fallbackPythonCommand);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public Map<String, Object> generateText(Map<String, Object> payload) {
        return execute("text-generation", payload);
    }

    public Map<String, Object> matchCases(Map<String, Object> payload) {
        return execute("case-similarity", payload);
    }

    public Map<String, Object> health() {
        return execute("health", Map.of());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> execute(String action, Map<String, Object> payload) {
        Path entrypoint = workerRoot.resolve("app.py").normalize();
        if (!entrypoint.startsWith(workerRoot) || !Files.isRegularFile(entrypoint)) {
            throw new IllegalStateException("AML智能分析Worker入口不存在: " + entrypoint);
        }
        Process process = null;
        try {
            process = new ProcessBuilder(pythonCommand, entrypoint.toString())
                    .directory(workerRoot.toFile())
                    .start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> readLimited(running.getInputStream(), "Worker响应"));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> readLimited(running.getErrorStream(), "Worker错误输出"));
            objectMapper.writeValue(process.getOutputStream(), Map.of(
                    "action", action,
                    "payload", payload == null ? Map.of() : payload));
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("AML智能分析执行超时（" + timeout.toSeconds() + "秒）");
            }
            String responseText = stdout.get(5, TimeUnit.SECONDS).trim();
            String errorText = stderr.get(5, TimeUnit.SECONDS).trim();
            if (responseText.isBlank()) {
                throw new IllegalStateException("AML智能分析未返回结果"
                        + (errorText.isBlank() ? "" : "：" + abbreviate(errorText)));
            }
            JsonNode response = objectMapper.readTree(responseText);
            if (process.exitValue() != 0 || "FAILED".equals(response.path("status").asText())) {
                String message = response.path("error").asText();
                if (message.isBlank()) message = errorText;
                throw new IllegalStateException("AML智能分析执行失败：" + abbreviate(message));
            }
            return objectMapper.convertValue(response, Map.class);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("AML智能分析Worker调用失败：" + ex.getMessage(), ex);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readLimited(InputStream stream, String label) {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException(label + "超过20MB限制");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(label + "读取失败", ex);
        }
    }

    private static Path resolveWorkerRoot(String configured) {
        Path requested = Path.of(configured);
        if (requested.isAbsolute()) return requested.normalize();
        Path applicationRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = applicationRoot.resolve(requested).normalize();
        if (Files.isDirectory(direct)) return direct;
        Path parentRelative = applicationRoot.resolve("..").resolve(requested).normalize();
        if (Files.isDirectory(parentRelative)) return parentRelative;
        return applicationRoot.resolve("backend").resolve(requested).normalize();
    }

    private static String abbreviate(String value) {
        String text = value == null ? "未知错误" : value.strip();
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "…";
    }
}
