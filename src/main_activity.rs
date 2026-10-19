use crate::engine;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use std::sync::Arc;

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(e) => {
            log::error!("MainActivity_initNative: failed to get JavaVM: {:?}", e);
            return;
        }
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(e) => {
            log::error!("MainActivity_initNative: failed to ref activity: {:?}", e);
            return;
        }
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm, &activity_ref);
    });
}
