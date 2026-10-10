use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    // transcribe-cpp-sys reconstructs its link line from a generic Unix
    // manifest that lists `pthread` and the C++ runtime. Bionic has neither a
    // separate libpthread (pthreads live in libc) nor a full libstdc++ (the
    // real C++ runtime is libc++_shared, which the app already bundles), so
    // satisfy the former with an empty archive and link the latter explicitly.
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        let out = PathBuf::from(env::var("OUT_DIR").unwrap());
        fs::write(out.join("libpthread.a"), b"!<arch>\n").unwrap();
        println!("cargo:rustc-link-search=native={}", out.display());
        println!("cargo:rustc-link-lib=dylib=c++_shared");
    } else {
        // When running tests on non-Android hosts (e.g. Linux x86_64 in CI),
        // android_log-sys requests `-llog` which does not exist in desktop Linux glibc.
        // Provide a stub archive `liblog.a` with position-independent code (-fPIC)
        // so the linker resolves `-llog` and log symbols for PIE test binaries.
        let out = PathBuf::from(env::var("OUT_DIR").unwrap());
        let stub_c = out.join("android_log_stub.c");
        let stub_source = b"#include <stdarg.h>\n\
int __android_log_write(int prio, const char *tag, const char *text) { (void)prio; (void)tag; (void)text; return 0; }\n\
int __android_log_buf_write(int bufID, int prio, const char *tag, const char *text) { (void)bufID; (void)prio; (void)tag; (void)text; return 0; }\n\
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }\n\
int __android_log_vprint(int prio, const char *tag, const char *fmt, va_list ap) { (void)prio; (void)tag; (void)fmt; (void)ap; return 0; }\n\
void __android_log_assert(const char *cond, const char *tag, const char *fmt, ...) { (void)cond; (void)tag; (void)fmt; }\n\
int __android_log_is_loggable(int prio, const char *tag, int default_prio) { (void)prio; (void)tag; (void)default_prio; return 1; }\n\
int __android_log_is_loggable_len(int prio, const char *tag, unsigned long size, int default_prio) { (void)prio; (void)tag; (void)size; (void)default_prio; return 1; }\n\
void __android_log_write_log_message(void *msg) { (void)msg; }\n";
        let _ = fs::write(&stub_c, stub_source);
        let stub_o = out.join("android_log_stub.o");
        let cc = env::var("CC").unwrap_or_else(|_| "cc".to_string());
        let compiled = std::process::Command::new(&cc)
            .args([
                "-fPIC",
                "-O2",
                "-c",
                stub_c.to_str().unwrap(),
                "-o",
                stub_o.to_str().unwrap(),
            ])
            .status()
            .map(|s| s.success())
            .unwrap_or(false);

        if compiled {
            let ar = env::var("AR").unwrap_or_else(|_| "ar".to_string());
            let _ = std::process::Command::new(&ar)
                .args([
                    "rcs",
                    out.join("liblog.a").to_str().unwrap(),
                    stub_o.to_str().unwrap(),
                ])
                .status();
        } else {
            let _ = fs::write(out.join("liblog.a"), b"!<arch>\n");
        }
        println!("cargo:rustc-link-search=native={}", out.display());
    }
}
