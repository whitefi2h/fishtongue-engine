# FishTongue Engine

This fork preserves Lexurgy Core and its rule language. FishTongue-specific
desktop behavior lives in the `desktop-api` module.

## Protocol

The desktop process starts with:

```text
host=127.0.0.1 port=0 protocolVersion=2 authToken=<256-bit token>
```

Only loopback binding is accepted. Every HTTP request requires the generated
Bearer token. Standard output is reserved for the single readiness handshake;
logs are written to standard error.

## Build

```powershell
.\gradlew.bat test desktop-api:buildFatJar cyclonedxBom
```

The release pipeline combines the fat JAR with a verified Temurin 21 runtime
created by `jlink`. Generated runtime and release archives are not committed.
The upstream Gradle toolchain remains Java 11 and the bytecode target remains
Java 8. The redistributed runtime is separately built from Temurin 21.

Lexurgy remains GPL-3.0. Keep `LICENSE`, upstream copyright notices, and all
runtime `legal/` files with redistributed artifacts.
