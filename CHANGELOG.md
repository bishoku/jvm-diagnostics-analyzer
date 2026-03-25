# Changelog

All notable changes to this project will be documented in this file.

## [2.0.4] - Unreleased
### Added
- Dynamic MAT heap allocation bounded by `DYNAMIC_MAX_MAT_MEMORY` and uploaded file size.
- New endpoint `/api/mat/config` to check MAT memory configuration.
- UI warnings on Heap Dump and MCP pages when MAT heap might be insufficient for the uploaded `.hprof` file.

## [2.0.3]
### Added
- SSE MCP chat streaming integration.

## [2.0.2]
### Changed
- Refactored `InMemoryChatMemory` to `MessageWindowChatMemory` and removed the associated dependency.

## [2.0.1]
### Added
- Registered `HeapDumpMcpTools` as Spring AI method tools via `McpToolConfig`.

## [2.0.0]
### Added
- Added `spring-boot-starter-webmvc-test` dependency.
- Added mocking for `McpSessionManager` and `McpLogService` in `AnalysisControllerTest`.
