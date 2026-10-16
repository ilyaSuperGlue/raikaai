// RaikaAI PTY bridge: minimal forkpty-style implementation for
// com.termux.terminal.JNI (vendored terminal-emulator, Apache 2.0).
//
// Implements the 4 native methods with plain POSIX calls so no libutil is
// needed on bionic: open /dev/ptmx, grantpt/unlockpt/ptsname, fork, setsid,
// open slave, dup2 onto stdio, exec. The master fd is returned to Java.

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_errno(JNIEnv* env, const char* msg) {
    jclass ex = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (ex != NULL) {
        char buf[256];
        snprintf(buf, sizeof(buf), "%s: %s", msg, strerror(errno));
        (*env)->ThrowNew(env, ex, buf);
    }
}

// Opens a pty master, returns master fd and fills slaveName. -1 on error.
static int open_pty_master(char* slaveName, size_t slaveLen) {
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return -1;
    }
    char* name = ptsname(master);
    if (name == NULL) {
        close(master);
        return -1;
    }
    strncpy(slaveName, name, slaveLen - 1);
    slaveName[slaveLen - 1] = '\0';
    return master;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(JNIEnv* env, jclass clazz, jstring cmd,
                                              jstring cwd, jobjectArray args, jobjectArray envVars,
                                              jintArray processId, jint rows, jint columns,
                                              jint cellWidth, jint cellHeight) {
    (void) clazz;
    char slaveName[64];
    int master = open_pty_master(slaveName, sizeof(slaveName));
    if (master < 0) {
        throw_errno(env, "openpty");
        return -1;
    }

    const char* cmdStr = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char* cwdStr = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (cmdStr == NULL || cwdStr == NULL) {
        if (cmdStr != NULL) (*env)->ReleaseStringUTFChars(env, cmd, cmdStr);
        if (cwdStr != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwdStr);
        close(master);
        throw_errno(env, "GetStringUTFChars");
        return -1;
    }

    // Copy argv + env into plain C arrays before forking.
    jsize argc = (*env)->GetArrayLength(env, args);
    char** argv = malloc(sizeof(char*) * (argc + 1));
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char* c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    jsize envc = (*env)->GetArrayLength(env, envVars);
    char** envp = malloc(sizeof(char*) * (envc + 1));
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
        const char* c = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    // Window size for the new pty (best effort).
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) columns;
    ws.ws_xpixel = (unsigned short) cellWidth;
    ws.ws_ypixel = (unsigned short) cellHeight;
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, cmd, cmdStr);
        (*env)->ReleaseStringUTFChars(env, cwd, cwdStr);
        close(master);
        throw_errno(env, "fork");
        return -1;
    }
    if (pid == 0) {
        // Child: new session, slave becomes stdio, exec target.
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(cwdStr) != 0) _exit(127);
        execve(cmdStr, argv, envp);
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, cmd, cmdStr);
    (*env)->ReleaseStringUTFChars(env, cwd, cwdStr);
    jint pidOut[1] = {(jint) pid};
    (*env)->SetIntArrayRegion(env, processId, 0, 1, pidOut);
    // Parent keeps argv/envp copies only until here; child has its own copies
    // via fork. Free parent copies (leak-free, child unaffected).
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    return master;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* env, jclass clazz, jint fd, jint rows,
                                             jint cols, jint cellWidth, jint cellHeight) {
    (void) env;
    (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) cellWidth;
    ws.ws_ypixel = (unsigned short) cellHeight;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv* env, jclass clazz, jint processId) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t w;
    do {
        w = waitpid((pid_t) processId, &status, 0);
    } while (w < 0 && errno == EINTR);
    if (w < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv* env, jclass clazz, jint fileDescriptor) {
    (void) env;
    (void) clazz;
    close(fileDescriptor);
}
