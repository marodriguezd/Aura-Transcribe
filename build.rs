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
        // Provide a stub archive `liblog.a` so the linker resolves `-llog` and log symbols.
        let out = PathBuf::from(env::var("OUT_DIR").unwrap());
        let stub_c = out.join("android_log_stub.c");
        let _ = fs::write(
            &stub_c,
            b"int __android_log_write() { return 0; }\n\
              int __android_log_buf_write() { return 0; }\n\
              int __android_log_print() { return 0; }\n\
              int __android_log_vprint() { return 0; }\n",
        );
        let stub_o = out.join("android_log_stub.o");
        let cc = env::var("CC").unwrap_or_else(|_| "cc".to_string());
        let compiled = std::process::Command::new(&cc)
            .args([
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
