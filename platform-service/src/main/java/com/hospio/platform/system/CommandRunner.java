package com.hospio.platform.system;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The only way System health runs a program: {@code systemctl} with read-only verbs ({@code show}, {@code list-units}), started
 * directly (no shell, so nothing in an argument is ever interpreted), with arguments checked against a strict pattern, a short time
 * limit and a cap on what is read back. Nothing here changes the server.
 */
@Component
public class CommandRunner {

    /** systemctl's read-only verbs this runner allows. */
    static final Set<String> VERBS = Set.of("show", "list-units");
    /** Unit names, property lists and the few fixed options: letters, digits, '-', '_', '.', '@', ',', '='. */
    static final Pattern SAFE_ARG = Pattern.compile("^[A-Za-z0-9@._,=-]{1,200}$");
    private static final int MAX_OUTPUT = 512 * 1024;
    private static final java.util.concurrent.ExecutorService READERS = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** The result: the exit code (-1 when it didn't finish in time or couldn't start) and standard output. */
    public record Result(int exit, String out) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** Runs {@code systemctl <verb> <args...>}; refuses any other verb or an argument outside {@link #SAFE_ARG}. */
    public Result systemctl(String verb, List<String> args, long timeoutMs) {
        if (!VERBS.contains(verb)) {
            throw new IllegalArgumentException("systemctl " + verb + " is not allowed");
        }
        List<String> command = new ArrayList<>(List.of("systemctl", verb, "--no-pager"));
        for (String a : args) {
            if (a == null || !SAFE_ARG.matcher(a).matches()) {
                throw new IllegalArgumentException("Refused argument for systemctl");
            }
            command.add(a);
        }
        return run(command, timeoutMs);
    }

    Result run(List<String> command, long timeoutMs) {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return new Result(-1, "");
        }
        try {
            p.getOutputStream().close();
            // Read on its own thread so a program that hangs without closing its output is still stopped at the time limit.
            Process proc = p;
            java.util.concurrent.CompletableFuture<String> out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return read(proc.getInputStream());
                } catch (IOException e) {
                    return "";
                }
            }, READERS);
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "");
            }
            return new Result(p.exitValue(), out.get(1, TimeUnit.SECONDS));
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            p.destroyForcibly();
            return new Result(-1, "");
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (buf.size() < MAX_OUTPUT) {
                buf.write(chunk, 0, Math.min(n, MAX_OUTPUT - buf.size()));
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
