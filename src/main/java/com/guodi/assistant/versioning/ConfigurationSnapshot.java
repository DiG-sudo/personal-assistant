package com.guodi.assistant.versioning;

import java.util.Map;

/** Optional component contract for immutable, JSON-safe settings. Never return credentials. */
public interface ConfigurationSnapshot {
    Map<String, Object> versionedSettings();
}
