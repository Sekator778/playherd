# LazyFS (https://github.com/dsrhaslab/lazyfs, MIT, see its LICENSE) built from a pinned commit, next to a Java 21 JDK.
FROM eclipse-temurin:21-jdk
ENV CMAKE_POLICY_VERSION_MINIMUM=3.5
ARG LAZYFS_COMMIT=fa7d32ee9710d55a7e529ac3c375f6616563c78b
RUN apt-get update && apt-get install -y --no-install-recommends g++ cmake make git libfuse3-dev fuse3 procps ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && echo user_allow_other >> /etc/fuse.conf
RUN git clone https://github.com/dsrhaslab/lazyfs /opt/lazyfs-src && cd /opt/lazyfs-src && git checkout "$LAZYFS_COMMIT" \
    && cd libs/libpcache && ./build.sh && cd ../../lazyfs && ./build.sh \
    && cp /opt/lazyfs-src/LICENSE /opt/LICENSE.lazyfs && ls -l /opt/lazyfs-src/lazyfs/build/lazyfs
COPY in-container.sh /in-container.sh
RUN chmod +x /in-container.sh
ENTRYPOINT ["/in-container.sh"]
