FROM jupyter/minimal-notebook:latest

USER root

# JRE для Kotlin-ядра (kotlin-jupyter-kernel ищет JDK в KOTLIN_JUPYTER_JAVA_HOME / JAVA_HOME)
RUN apt-get update && \
    apt-get install --yes --no-install-recommends default-jre-headless && \
    rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/default-java

RUN pip install --no-cache-dir \
    "jupyter-mcp-server>=0.15.0" \
    "jupyterlab==4.4.1" \
    "jupyter-collaboration==4.0.2" \
    "jupyter-mcp-tools>=0.1.4" \
    "ipykernel" \
    "pycrdt" \
    "kotlin-jupyter-kernel" && \
    python -m kotlin_kernel fix-kernelspec-location && \
    jupyter server extension enable --py jupyter_mcp_server && \
    jupyter server extension list

# установки под root создали /home/jovyan/.local с владельцем root — возвращаем каталог jovyan'у
RUN chown -R $NB_UID:$NB_GID /home/jovyan

USER $NB_UID

# Прелюдии языков — для прогрева ядер на старте контейнера (docker-entrypoint.sh)
COPY src/main/resources/runtime/python-prelude.py /opt/preludes/python-prelude.py
COPY src/main/resources/runtime/kotlin-prelude.kts /opt/preludes/kotlin-prelude.kts

# Предзагрузка зависимостей kotlin-прелюдии (klaxon): kernel резолвит их в кэш (~/.m2)
# на этапе сборки, чтобы первый запуск kotlin-скрипта с payload не требовал
# доступа к Maven Central во время работы
COPY --chown=$NB_UID:$NB_GID mcp-local/kotlin-warmup.ipynb /tmp/kotlin-warmup.ipynb
RUN jupyter nbconvert --to notebook --execute \
      --ExecutePreprocessor.timeout=600 \
      --output-dir=/tmp \
      --output=warmup-executed.ipynb \
      /tmp/kotlin-warmup.ipynb && \
    rm -f /tmp/kotlin-warmup.ipynb /tmp/warmup-executed.ipynb

# Старт Jupyter + создание и прогрев ядер python3/kotlin (пул сервиса усыновляет их).
# Exec-бит выставлен на исходном файле — COPY сохраняет права, chmod в образе не нужен
COPY mcp-local/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

EXPOSE 4040

ENTRYPOINT ["docker-entrypoint.sh"]

