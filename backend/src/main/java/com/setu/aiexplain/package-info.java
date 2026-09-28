/**
 * Day 14 §AI: {@code ai-explain} — the only module allowed to call Spring
 * AI / Ollama (TRD §6). Deliberately has no {@code domain} package: no JPA
 * entities, no datasource of its own (AGENTS.md). Every fact it surfaces
 * comes from another module's already-authorized service or a narrow
 * no-auth cross-module port, exactly like every other inter-module
 * dependency in this codebase.
 */
package com.setu.aiexplain;
