# syntax=docker/dockerfile:1.7
#
# android-editor: build Android apps and root Android phones, with a local AI
# coding agent. Nothing else.
#
#   Build apps:  OpenJDK 17 and 11, Maven, Android SDK (cmdline-tools,
#                platform-tools, build-tools 36 + 33, platforms 36 + 33;
#                Gradle fetches any other SDK version a project asks for),
#                Gradle 8 as `gradle` and Gradle 7 as `gradle7` (on JDK 11),
#                NDK optional
#   Root phones: adb, fastboot, Magisk APK + magiskboot, payload-dumper-go
#                (boot.img out of OTAs), mkbootimg/unpack_bootimg/repack_bootimg,
#                avbtool, simg2img/img2simg, lpunpack/lpmake/lpdump (super.img),
#                sdat2img, ext4/erofs/f2fs tools, mtkclient (MediaTek BROM),
#                edl (Qualcomm EDL), heimdall (Samsung), android-full-backup
#   AI:          OpenCode, wired to two models served by Ollama on the
#                host (see .env): qwen3.8:27b default, muse-glimmer:30b
#                second opinion — pick your own with bin/pick-model. Cline
#                and Roo Code run here through VS Code Dev Containers with
#                the same two models.
#
# Google ships Android build-tools for linux/x86_64 only, so the image is
# linux/amd64 (Rosetta on Apple silicon).

ARG UBUNTU=ubuntu:24.04

# ---------------------------------------------------------------- python tools
# Built in their own stage so compilers and headers stay out of the final image.
FROM ${UBUNTU} AS python-tools
ARG DEBIAN_FRONTEND=noninteractive
ARG EDL_TAG=3.52.1
ARG MTKCLIENT_TAG=v2.1.4.1
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git python3 python3-venv python3-dev build-essential \
    && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    python3 -m venv /opt/venv; \
    /opt/venv/bin/pip install --no-cache-dir -U pip wheel setuptools; \
    # MediaTek BROM/preloader client (bootloader unlock, partition read/write).
    # Its Qt GUI dependencies are skipped; the CLI needs only these.
    /opt/venv/bin/pip install --no-cache-dir pyusb pyserial pycryptodome pycryptodomex colorama fusepy mfusepy; \
    /opt/venv/bin/pip install --no-cache-dir --no-deps "git+https://github.com/bkerler/mtkclient@${MTKCLIENT_TAG}"; \
    # Qualcomm EDL (Sahara/Firehose) client.
    /opt/venv/bin/pip install --no-cache-dir "git+https://github.com/bkerler/edl@${EDL_TAG}"; \
    find /opt/venv -name '__pycache__' -prune -exec rm -rf {} +

# ---------------------------------------------------------------- liblp tools
# lpunpack / lpmake / lpdump / lpadd: read and build the super.img that holds
# system, vendor and product on every device launched with Android 10+. Static
# binaries, so clang and the headers stay out of the final image.
FROM ${UBUNTU} AS liblp
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git clang binutils zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    git clone -q --depth 1 https://github.com/LonelyFool/lpunpack_and_lpmake /src; \
    cd /src && bash ./make.sh >/dev/null 2>&1; \
    mkdir -p /out && cp bin/lpunpack bin/lpmake bin/lpdump bin/lpadd bin/lpflash /out/

# ---------------------------------------------------------------- final image
FROM ${UBUNTU}
ARG DEBIAN_FRONTEND=noninteractive
ARG TARGETARCH

ARG CMDLINE_TOOLS_VERSION=15859902
ARG BUILD_TOOLS_VERSION=36.0.0
ARG PLATFORM=android-36
# Older API level and matching build-tools, for projects that target it.
ARG BUILD_TOOLS_LEGACY=33.0.2
ARG PLATFORM_LEGACY=android-33
ARG NDK_VERSION=28.2.13676358
ARG INSTALL_NDK=0
ARG GRADLE_VERSION=8.14.6
# Gradle 7 for older projects; runs on JDK 11 (see the gradle7 wrapper below).
ARG GRADLE7_VERSION=7.5.1
ARG PAYLOAD_DUMPER_GO_VERSION=2.1.0
ARG AOSP_TAG=android-16.0.0_r4
ARG MAGISK_VERSION=v30.7
ARG OPENCODE_VERSION=1.18.35

RUN apt-get update && apt-get install -y --no-install-recommends \
      # fetching, archives, basics
      ca-certificates curl wget git git-lfs sudo locales tzdata \
      unzip zip p7zip-full xz-utils zstd lz4 bzip2 brotli gzip tar cpio \
      # editors, shell comforts, inspection
      nano vim less tmux htop rsync jq ripgrep file tree diffutils patch \
      bsdextrautils bc coreutils findutils procps iproute2 iputils-ping \
      netcat-openbsd openssh-client ca-certificates \
      python3 openjdk-17-jdk-headless openjdk-11-jdk-headless maven fonts-dejavu-core \
      # USB / phone access
      libusb-1.0-0 libfuse2t64 libfuse3-3 usbutils udev \
      android-sdk-platform-tools-common android-sdk-libsparse-utils heimdall-flash \
      # firmware images: ext4, EROFS, F2FS, device trees, blob inspection
      e2fsprogs erofs-utils f2fs-tools dosfstools device-tree-compiler \
      binwalk abootimg \
    && rm -rf /var/lib/apt/lists/* \
    && locale-gen en_US.UTF-8

ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk GRADLE_HOME=/opt/gradle JAVA_HOME=/opt/java
ENV PATH=/opt/venv/bin:/opt/opencode/bin:/opt/tools/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools:${GRADLE_HOME}/bin:${PATH}
# Two JDKs: 17 is the default, 11 is there for older Gradle/AGP combinations.
RUN set -eux; \
    ln -sfn /usr/lib/jvm/java-17-openjdk-amd64 /opt/java-17; \
    ln -sfn /usr/lib/jvm/java-11-openjdk-amd64 /opt/java-11; \
    ln -sfn /opt/java-17 /opt/java

# ---- non-root user. Created before the SDK so that step can set ownership as
# it goes: a later `chown -R` on the SDK would copy all ~800 MB into a new layer.
ARG USERNAME=dev
ARG UID=1000
ARG GID=1000
RUN set -eux; \
    if getent passwd ${UID} >/dev/null; then userdel -r "$(getent passwd ${UID} | cut -d: -f1)"; fi; \
    groupadd -g ${GID} ${USERNAME} || true; \
    useradd -m -u ${UID} -g ${GID} -s /bin/bash ${USERNAME}; \
    usermod -aG sudo,plugdev,dialout ${USERNAME}; \
    echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME}; \
    mkdir -p /workspace && chown ${UID}:${GID} /workspace; \
    ln -sfn /workspace /app; \
    # Mount points for named volumes. Creating them here, owned by the dev user,
    # makes Docker seed a fresh volume with that ownership; without it a new
    # volume arrives root-owned and Gradle cannot write to it.
    mkdir -p /home/${USERNAME}/.gradle /home/${USERNAME}/.android /home/${USERNAME}/.config; \
    chown -R ${UID}:${GID} /home/${USERNAME}

# ---- Android SDK: one platform and one build-tools; Gradle downloads any other
# version a project declares (the SDK directory is writable by the dev user).
RUN set -eux; \
    mkdir -p ${ANDROID_HOME}/cmdline-tools; \
    curl -fsSL -o /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    unzip -q /tmp/clt.zip -d /tmp/clt && mv /tmp/clt/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest; \
    rm -rf /tmp/clt /tmp/clt.zip; \
    yes | sdkmanager --licenses >/dev/null; \
    pkgs="platform-tools build-tools;${BUILD_TOOLS_VERSION} platforms;${PLATFORM}"; \
    pkgs="$pkgs build-tools;${BUILD_TOOLS_LEGACY} platforms;${PLATFORM_LEGACY}"; \
    if [ "${INSTALL_NDK}" = "1" ]; then pkgs="$pkgs ndk;${NDK_VERSION} cmake;3.22.1"; fi; \
    sdkmanager --install $pkgs; \
    rm -rf ${ANDROID_HOME}/.downloadIntermediates ${ANDROID_HOME}/.temp /root/.android; \
    # Old layout compatibility: scripts and docs that expect $ANDROID_HOME/tools/bin/sdkmanager.
    ln -sfn ${ANDROID_HOME}/cmdline-tools/latest ${ANDROID_HOME}/tools; \
    chown -R ${UID}:${GID} ${ANDROID_HOME}
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${NDK_VERSION}

# ---- Gradle (projects normally use their own ./gradlew; these create wrappers)
# `gradle` is 8.x on JDK 17; `gradle7` is 7.5.x on JDK 11 for older projects.
RUN set -eux; \
    for v in ${GRADLE_VERSION} ${GRADLE7_VERSION}; do \
      curl -fsSL -o /tmp/g.zip "https://services.gradle.org/distributions/gradle-$v-bin.zip"; \
      unzip -q /tmp/g.zip -d /opt && rm /tmp/g.zip; \
    done; \
    ln -sfn /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME}; \
    printf '#!/bin/sh\nexec env JAVA_HOME=/opt/java-11 /opt/gradle-%s/bin/gradle "$@"\n' "${GRADLE7_VERSION}" > /usr/local/bin/gradle7; \
    chmod +x /usr/local/bin/gradle7

# ---- Rooting tools
RUN set -eux; \
    mkdir -p /opt/tools/bin /opt/tools/magisk; \
    case "${TARGETARCH:-amd64}" in arm64) arch=arm64 ;; *) arch=amd64 ;; esac; \
    # boot.img / init_boot.img out of OTA payload.bin
    curl -fsSL "https://github.com/ssut/payload-dumper-go/releases/download/${PAYLOAD_DUMPER_GO_VERSION}/payload-dumper-go_${PAYLOAD_DUMPER_GO_VERSION}_linux_${arch}.tar.gz" \
      | tar -xz -C /opt/tools/bin payload-dumper-go; \
    # boot image unpack/repack and Android Verified Boot (vbmeta) from AOSP
    git clone -q --depth 1 --branch ${AOSP_TAG} https://android.googlesource.com/platform/system/tools/mkbootimg /opt/tools/mkbootimg; \
    git clone -q --depth 1 --branch ${AOSP_TAG} https://android.googlesource.com/platform/external/avb /opt/tools/avb; \
    rm -rf /opt/tools/mkbootimg/.git /opt/tools/avb/.git; \
    for t in mkbootimg unpack_bootimg repack_bootimg; do \
      printf '#!/bin/sh\nexec python3 /opt/tools/mkbootimg/%s.py "$@"\n' "$t" > /opt/tools/bin/$t; done; \
    printf '#!/bin/sh\nexec python3 /opt/tools/avb/avbtool.py "$@"\n' > /opt/tools/bin/avbtool; \
    # Magisk: the APK to install on the phone, and magiskboot (shipped inside it)
    # to unpack, patch and repack boot images here instead of on the device.
    curl -fsSL -o /opt/tools/magisk/Magisk-${MAGISK_VERSION}.apk \
      "https://github.com/topjohnwu/Magisk/releases/download/${MAGISK_VERSION}/Magisk-${MAGISK_VERSION}.apk"; \
    ln -s Magisk-${MAGISK_VERSION}.apk /opt/tools/magisk/Magisk.apk; \
    case "${TARGETARCH:-amd64}" in arm64) abi=arm64-v8a ;; *) abi=x86_64 ;; esac; \
    unzip -o -j -q /opt/tools/magisk/Magisk.apk "lib/$abi/libmagiskboot.so" -d /opt/tools/bin \
      && mv /opt/tools/bin/libmagiskboot.so /opt/tools/bin/magiskboot; \
    # OTA system.new.dat (+ .br) -> system.img
    curl -fsSL -o /opt/tools/bin/sdat2img https://raw.githubusercontent.com/xpirt/sdat2img/master/sdat2img.py; \
    chmod +x /opt/tools/bin/*
COPY --from=liblp /out/ /opt/tools/bin/
ENV MAGISK_APK=/opt/tools/magisk/Magisk.apk

# mtkclient + edl, built above. edl ships its CLIs as bin/*.py next to a package
# of the same name; -P keeps the script directory from shadowing the package.
COPY --from=python-tools /opt/venv /opt/venv
RUN for f in edl fhloaderparse; do \
      printf '#!/bin/sh\nexec /opt/venv/bin/python3 -P /opt/venv/bin/%s.py "$@"\n' "$f" > /opt/tools/bin/$f; \
      chmod +x /opt/tools/bin/$f; done

# ---- OpenCode
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in arm64) arch=arm64 ;; *) arch=x64 ;; esac; \
    mkdir -p /opt/opencode/bin; \
    curl -fsSL "https://github.com/anomalyco/opencode/releases/download/v${OPENCODE_VERSION}/opencode-linux-${arch}.tar.gz" \
      | tar -xz -C /opt/opencode/bin; \
    chmod +x /opt/opencode/bin/opencode

COPY docker/51-android.rules /etc/udev/rules.d/51-android.rules

COPY --chmod=755 docker/bin/ /usr/local/bin/
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/entrypoint.sh

ENV OLLAMA_HOST=http://host.docker.internal:11436
ENV GRADLE_USER_HOME=/home/${USERNAME}/.gradle
ENV ANDROID_USER_HOME=/home/${USERNAME}/.android

USER ${USERNAME}
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["bash"]
