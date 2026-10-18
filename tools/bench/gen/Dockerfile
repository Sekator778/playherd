# Load generator image of the bench (design §3, step 4): wrk2 (built from a pinned commit) + Gatling scenarios (built on the Mac).
# Build on the Mac for linux/amd64 (the bench box has no internet): tools/bench/build-gen.sh. The context is tools/bench/gen after `mvn package`.
FROM ubuntu:22.04 AS wrk2
RUN apt-get update && apt-get install -y --no-install-recommends build-essential libssl-dev zlib1g-dev git ca-certificates && rm -rf /var/lib/apt/lists/*
ARG WRK2_COMMIT=44a94c17d8e6a0bac8559b53da76848e430cb7a7
# The LuaJIT bundled in wrk2 cannot create a state under amd64 emulation on an arm64 Mac (its allocator wants the low 4 GB): it is
# replaced by LuaJIT v2.1 (pinned commit) built with GC64 (no low-address allocator); the wrk2 sources are otherwise unchanged.
ARG LUAJIT_COMMIT=2a8e017ef1ece6a95b983ffca073d30609dce7c4
RUN git clone https://github.com/giltene/wrk2.git /wrk2 && cd /wrk2 && git checkout "$WRK2_COMMIT" \
 && rm -rf deps/luajit && git clone https://github.com/LuaJIT/LuaJIT.git deps/luajit && git -C deps/luajit checkout "$LUAJIT_COMMIT" \
 && sed -i "s/\bluaL_reg\b/luaL_Reg/g" src/*.c src/*.h \
 && sed -i 's|BUILDMODE=static|BUILDMODE=static XCFLAGS=-DLUAJIT_ENABLE_GC64|' Makefile \
 && make -j"$(nproc)" && ./wrk --version 2>&1 | head -3

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libssl3 zlib1g && rm -rf /var/lib/apt/lists/*
COPY --from=wrk2 /wrk2/wrk /usr/local/bin/wrk
COPY target/lib /gen/lib
COPY target/classes /gen/classes
COPY wrk/herd.lua /gen/herd.lua
WORKDIR /gen
ENV JAVA_TOOL_OPTIONS=""
