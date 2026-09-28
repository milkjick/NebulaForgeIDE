package com.nebulaforge.bridge;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.ProgressListener;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.GradleTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny Termux-side Gradle Tooling API worker. It deliberately has no Android dependencies.
 * Input is a Java properties file and output is line-oriented NEBULA_EVENT records so the
 * Android side can consume progress without depending on Gradle classes.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String,String> cli = args(args);
        if (cli.containsKey("self-test")) {
            GradleConnector.newConnector();
            System.out.println("NEBULA_SELF_TEST\tOK\tgradle-tooling-api=8.9");
            return;
        }
        if (!cli.containsKey("request")) throw new IllegalArgumentException("--request is required");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(cli.get("request")))) { p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
        File project = new File(required(p, "project"));
        String task = p.getProperty("task", "");
        if (cli.containsKey("tasks")) {
            listTasks(project, new File(required(p, "javaHome")), p.getProperty("gradleUserHome", ""));
            return;
        }
        if (task.isEmpty()) throw new IllegalArgumentException("missing task");
        File javaHome = new File(required(p, "javaHome"));
        String gradleUserHome = p.getProperty("gradleUserHome", "");
        long start = System.currentTimeMillis();
        AtomicLong events = new AtomicLong();
        emit("PROGRESS", "5", "连接 Gradle Tooling API");
        ProjectConnection connection = null;
        int code = 1;
        try {
            GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(project);
            if (!gradleUserHome.isEmpty()) connector.useGradleUserHomeDir(new File(gradleUserHome));
            connection = connector.connect();
            BuildLauncher build = connection.newBuild().forTasks(task).setJavaHome(javaHome)
                    .withArguments("--console=plain");
            build.addProgressListener(new ProgressListener() {
                @Override public void statusChanged(ProgressEvent event) {
                    long n = events.incrementAndGet();
                    int percent = (int)Math.min(95, 10 + (n % 85));
                    emit("PROGRESS", Integer.toString(percent), event.getDisplayName());
                }
            });
            build.setStandardOutput(new EventOutputStream(false));
            build.setStandardError(new EventOutputStream(true));
            emit("PROGRESS", "10", "开始执行 " + task);
            build.run();
            code = 0;
        } catch (Throwable t) {
            emit("ERR", "0", stack(t));
        } finally {
            if (connection != null) connection.close();
            emit("FINISHED", Integer.toString(code), Long.toString(System.currentTimeMillis() - start));
        }
        System.exit(code);
    }

    private static void listTasks(File project, File javaHome, String gradleUserHome) {
        ProjectConnection connection = null;
        try {
            GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(project);
            if (!gradleUserHome.isEmpty()) connector.useGradleUserHomeDir(new File(gradleUserHome));
            connection = connector.connect();
            GradleProject model = connection.getModel(GradleProject.class);
            emit("MODEL", "ROOT", model.getName());
            emitTasks(model);
            emit("MODEL", "FINISHED", "");
        } catch (Throwable t) {
            emit("ERR", "0", stack(t));
            System.exit(2);
        } finally { if (connection != null) connection.close(); }
    }

    private static void emitTasks(GradleProject model) {
        for (GradleTask task : model.getTasks()) {
            emit("TASK", task.getPath(), task.getName() + "\t" + task.getDescription());
        }
        for (GradleProject child : model.getChildren()) emitTasks(child);
    }

    private static final class EventOutputStream extends OutputStream {
        private final boolean error;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        EventOutputStream(boolean error) { this.error = error; }
        @Override public synchronized void write(int b) {
            if (b == '\n') flushLine(); else buf.write(b);
        }
        @Override public synchronized void flush() { if (buf.size() > 0) flushLine(); }
        private void flushLine() {
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            emit(error ? "ERR" : "LOG", "0", s.replace('\r',' ').trim());
        }
    }

    private static void emit(String kind, String value, String message) {
        String safe = message == null ? "" : message.replace("\t", " ").replace("\r", " ").replace("\n", " ");
        System.out.println("NEBULA_EVENT\t" + kind + "\t" + value + "\t" + safe);
        System.out.flush();
    }
    private static String required(Properties p, String k) { String v=p.getProperty(k); if(v==null||v.isEmpty()) throw new IllegalArgumentException("missing "+k); return v; }
    private static Map<String,String> args(String[] args) { Map<String,String> m=new HashMap<>(); for(int i=0;i+1<args.length;i+=2) if(args[i].startsWith("--")) m.put(args[i].substring(2),args[++i]); return m; }
    private static String stack(Throwable t) { StringWriter w=new StringWriter(); t.printStackTrace(new PrintWriter(w)); return w.toString(); }
}
