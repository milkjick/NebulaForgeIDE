#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <vector>
#include <cstdlib>

struct Pty {
    int master = -1;
    pid_t pid = -1;
};

static jlong ptr(jlong v) { return v; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeOpen(JNIEnv* env, jclass,
                                                    jstring shell,
                                                    jint rows, jint cols,
                                                    jobjectArray argv,
                                                    jobjectArray envp) {
    const char* sh = env->GetStringUTFChars(shell, nullptr);
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    // 失败时返回 **负 errno**（-EIO 兜底），让 Java 侧能把真实原因（EACCES/ENOENT/ENOEXEC）
    // 带回上层。Android 上 app 私有目录的可执行文件被系统拒绝时 execve 返回 EACCES，
    // 若只返回 0，上层只能看到「cannot execute」，无法区分 SELinux 拒绝 / 库缺失 / 格式错误。
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
        int err = errno;
        if (master >= 0) close(master);
        env->ReleaseStringUTFChars(shell, sh);
        return -(jlong) (err ? err : EIO);
    }
    char* slaveName = ptsname(master);
    if (!slaveName) {
        int err = errno;
        close(master);
        env->ReleaseStringUTFChars(shell, sh);
        return -(jlong) (err ? err : EIO);
    }

    std::vector<std::string> envValues;
    if (envp) {
        jsize n = env->GetArrayLength(envp);
        envValues.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto s = (jstring)env->GetObjectArrayElement(envp, i);
            const char* v = env->GetStringUTFChars(s, nullptr);
            envValues.emplace_back(v ? v : "");
            env->ReleaseStringUTFChars(s, v);
            env->DeleteLocalRef(s);
        }
    }

    // 上层传入的 argv 必须被真正使用：此前这里硬编码 {sh, "-i"}，Kotlin 侧的 argv 形同虚设，
    // 而强制 `-i` 会让 Termux 补丁版 bash 以交互式启动 —— 它会 source 编译期前缀
    // /data/data/com.termux/files/usr/etc/bash.bashrc；该路径在宿主命名空间属于别的应用私有目录
    // （不可穿越 → EACCES 而非 ENOENT），于是每次执行都会先吐一行
    //   bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied
    // 并被上层当作命令输出展示。现在由上层明确传 `--norc --noprofile -i` 即可根除该噪声。
    std::vector<std::string> argValues;
    if (argv) {
        jsize n = env->GetArrayLength(argv);
        argValues.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto s = (jstring)env->GetObjectArrayElement(argv, i);
            const char* v = env->GetStringUTFChars(s, nullptr);
            argValues.emplace_back(v ? v : "");
            env->ReleaseStringUTFChars(s, v);
            env->DeleteLocalRef(s);
        }
    }

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        struct winsize ws{};
        ws.ws_row = rows > 0 ? rows : 24;
        ws.ws_col = cols > 0 ? cols : 80;
        ioctl(slave, TIOCSWINSZ, &ws);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        for (const auto& value : envValues) {
            putenv(strdup(value.c_str()));
        }
        // 有显式 argv 就照用（argv[0] 由上层给出，例如 shell 自身或 proot），否则退回 {sh, "-i"}。
        std::vector<char*> execArgs;
        if (!argValues.empty()) {
            execArgs.reserve(argValues.size() + 1);
            for (auto& value : argValues) execArgs.push_back(const_cast<char*>(value.c_str()));
        } else {
            execArgs.push_back(const_cast<char*>(sh));
            execArgs.push_back(const_cast<char*>("-i"));
        }
        execArgs.push_back(nullptr);
        execv(sh, execArgs.data());
        {
            // execve 失败：把真实 errno 直接写进 pty，让上层看到的不是静默的空输出。
            // 13=EACCES（SELinux/noexec 拒绝）、2=ENOENT（解释器或库缺失）、8=ENOEXEC（格式错误）。
            int err = errno;
            char buf[512];
            int n = snprintf(buf, sizeof(buf),
                             "\n__NEBULA_EXEC_FAIL__ errno=%d strerror=%s path=%s\n",
                             err, strerror(err), sh);
            if (n > 0) {
                ssize_t ignored = write(STDERR_FILENO, buf, (size_t) n);
                (void) ignored;
            }
        }
        _exit(127);
    }
    env->ReleaseStringUTFChars(shell, sh);
    if (pid < 0) {
        int err = errno;
        close(master);
        return -(jlong) (err ? err : EIO);
    }
    auto* p = new Pty{master, pid};
    return reinterpret_cast<jlong>(p);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeRead(JNIEnv* env, jclass, jlong handle, jbyteArray out) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p || p->master < 0) return -1;
    jsize n = env->GetArrayLength(out);
    jbyte* buf = env->GetByteArrayElements(out, nullptr);
    ssize_t r = read(p->master, buf, n);
    if (r > 0) env->ReleaseByteArrayElements(out, buf, JNI_COMMIT);
    else env->ReleaseByteArrayElements(out, buf, JNI_ABORT);
    return (jint)r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeWrite(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p || p->master < 0) return -1;
    jsize n = env->GetArrayLength(data);
    if (n <= 0) return 0;
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    // PTY 主设备在从端尚未排空输入队列时会**短写**（write 只接受一部分字节并返回写入量）。
    // 原实现只 write 一次且丢弃返回值，于是较长的命令会被静默截断：真机上的表现正是
    // 终端只回显半截脚本、`cd` 看似失败、退出码 125、marker 永远不打印。
    // 这里循环写完所有字节；EINTR 直接重试，EAGAIN/EWOULDBLOCK 短暂让步后继续。
    jint total = 0;
    while (total < n) {
        ssize_t r = write(p->master, buf + total, (size_t) (n - total));
        if (r > 0) { total += (jint) r; continue; }
        if (r < 0 && errno == EINTR) continue;
        if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) { usleep(2000); continue; }
        break;
    }
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return total;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeResize(JNIEnv*, jclass, jlong handle, jint rows, jint cols) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p || p->master < 0) return -1;
    struct winsize ws{};
    ws.ws_row = rows; ws.ws_col = cols;
    return ioctl(p->master, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeSignal(JNIEnv*, jclass, jlong handle, jint sig) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p || p->pid <= 0) return -1;
    return kill(-p->pid, sig);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeWait(JNIEnv*, jclass, jlong handle) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p || p->pid <= 0) return -1;
    int status = 0;
    if (waitpid(p->pid, &status, 0) < 0) return -1;
    p->pid = -1; // 已回收，标记为无子进程，避免 nativeClose 二次 waitpid 误伤复用的 pid
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nebulaforge_core_pty_NativePty_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* p = reinterpret_cast<Pty*>(ptr(handle));
    if (!p) return;
    if (p->master >= 0) { close(p->master); p->master = -1; }
    if (p->pid > 0) {
        // 真机踩坑：此前 nativeClose 只对进程组 SIGHUP、从不 waitpid，于是 fork 出来的
        // [bash] 子进程退出后**变成僵尸且永不回收**——每执行一条命令残留一个，一个会话
        // 可累积到数十个（`ps` 里 PPID=应用、STAT=Z），表现为「进程僵死 / 越用越卡」。
        // 这里在关闭 PTY 时把子进程真正回收，且全程有界、绝不无限阻塞调用方：
        //   1) 先 SIGHUP 进程组，给它一个优雅退出的窗口；
        //   2) 窗口内没收掉就 SIGKILL 进程组，再做最终回收。
        int status = 0;
        bool reaped = false;
        kill(-p->pid, SIGHUP);
        for (int i = 0; i < 20; ++i) {           // ~200ms 优雅退出窗口
            pid_t r = waitpid(p->pid, &status, WNOHANG);
            if (r == p->pid || (r < 0 && errno == ECHILD)) { reaped = true; break; }
            usleep(10 * 1000);
        }
        if (!reaped) {
            kill(-p->pid, SIGKILL);
            for (int i = 0; i < 100; ++i) {      // ~1s 强制回收窗口
                pid_t r = waitpid(p->pid, &status, WNOHANG);
                if (r == p->pid || (r < 0 && errno == ECHILD)) { reaped = true; break; }
                usleep(10 * 1000);
            }
        }
        (void) reaped;
        p->pid = -1;
    }
    delete p;
}
