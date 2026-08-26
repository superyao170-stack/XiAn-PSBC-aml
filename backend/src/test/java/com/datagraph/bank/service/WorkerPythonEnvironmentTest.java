package com.datagraph.bank.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerPythonEnvironmentTest {
    @TempDir
    Path tempDir;

    @Test
    void retainedWorkersPreferTheSharedEnvironment() throws Exception {
        Path workerRoot = tempDir.resolve("workers/aml-intelligence");
        Path sharedPython = tempDir.resolve("workers/.venv/bin/python");
        Files.createDirectories(workerRoot);
        Files.createDirectories(sharedPython.getParent());
        Files.createFile(sharedPython);

        assertEquals(sharedPython.toString(),
                WorkerPythonEnvironment.resolve("", workerRoot, "python3"));
    }

    @Test
    void explicitDeploymentInterpreterWins() throws Exception {
        Path workerRoot = tempDir.resolve("workers/structured-case-identification");
        Files.createDirectories(workerRoot);

        assertEquals("/opt/aml-venv/bin/python",
                WorkerPythonEnvironment.resolve(
                        "/opt/aml-venv/bin/python", workerRoot, "python3"));
    }
}
