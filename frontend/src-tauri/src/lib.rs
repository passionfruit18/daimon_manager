//! Tauri shell for DAIMon Desktop.
//!
//! The UI talks to the local Quarkus daemon over HTTP (default http://127.0.0.1:7777).
//! This shell only exposes the configured daemon URL so the UI can discover it at runtime.

/// Returns the daemon base URL, overridable with the `DAIMON_URL` environment variable.
#[tauri::command]
fn daemon_url() -> String {
    std::env::var("DAIMON_URL").unwrap_or_else(|_| "http://127.0.0.1:7777".to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![daemon_url])
        .run(tauri::generate_context!())
        .expect("error while running DAIMon Desktop");
}
