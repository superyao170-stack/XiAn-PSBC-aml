package com.datagraph.bank.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves the shared Python runtime used by the retained AML workers. */
final class WorkerPythonEnvironment {
    private WorkerPythonEnvironment() {
    }

    static String resolve(String configured, Path workerRoot, String fallback) {
        if (configured != null && !configured.isBlank()) return configured.trim();

        Path workersRoot = workerRoot.toAbsolutePath().normalize().getParent();
        List<Path> candidates = List.of(
                workersRoot.resolve(".venv/bin/python"),
                workersRoot.resolve(".venv/Scripts/python.exe"),
                workerRoot.resolve(".venv/bin/python"),
                workerRoot.resolve(".venv/Scripts/python.exe"));
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .orElse(fallback == null || fallback.isBlank() ? "python3" : fallback.trim());
    }
}
