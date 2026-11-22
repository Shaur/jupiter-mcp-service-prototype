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

EXPOSE 4040

CMD ["jupyter", "lab", "--port=4040", "--ip=0.0.0.0", \
     "--IdentityProvider.token=MY_TOKEN", "--no-browser"]

