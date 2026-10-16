# Окружение release-сборки как на buildserver F-Droid: Debian trixie, OpenJDK 25
# из Debian и NDK r28c (версия NDK по умолчанию в AGP 9.2). От JDK и NDK зависит
# содержимое APK, поэтому APK из этого образа совпадает со сборкой F-Droid.
# Файл одинаковый в fdroid/ частных веток и в ветке deploy (сборка в CI),
# менять оба.
#
# Пакеты Debian берутся на момент сборки образа, поэтому новая дата базового
# образа означает и свежий OpenJDK. Смена даты в ветке deploy пересобирает
# образ (image.yml).
FROM docker.io/library/debian:trixie-20261005

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates git openjdk-25-jdk-headless sdkmanager \
    && rm -rf /var/lib/apt/lists/*

# sdkmanager из trixie (0.6) не пишет package.xml для платформ 36+, из-за
# этого AGP считает платформу 37.0 неустановленной и пытается скачать ее сам.
# Версия из backports пишет. apksigcopier - тот же перенос подписи, которым
# F-Droid проверяет APK разработчика.
RUN echo "deb http://deb.debian.org/debian trixie-backports main" \
        > /etc/apt/sources.list.d/backports.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        sdkmanager/trixie-backports apksigcopier \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk

# без platform-tools в образе AGP скачивает их при каждой сборке
RUN mkdir -p "$ANDROID_HOME" \
    && sdkmanager platform-tools "platforms;android-37.0" "build-tools;36.0.0" \
        "ndk;28.2.13676358" \
    && yes | sdkmanager --licenses >/dev/null

# Только для apksigner: build-tools 35+ подписывают так, что apksigcopier F-Droid
# не может перенести подпись. AGP собирает с 36.0.0.
RUN sdkmanager "build-tools;34.0.0"

WORKDIR /home/vagrant/build/dev.inkov.cocosations
