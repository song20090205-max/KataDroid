FROM ubuntu:24.04 AS build

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git zlib1g-dev libzip-dev libeigen3-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 --branch v1.16.4 https://github.com/lightvector/KataGo.git
WORKDIR /src/KataGo/cpp
RUN cmake . -DUSE_BACKEND=EIGEN -DCMAKE_BUILD_TYPE=Release -DNO_GIT_REVISION=1 \
    && make -j"$(nproc)"

FROM ubuntu:24.04
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    libstdc++6 zlib1g libzip4 libgomp1 python3 python3-pip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/KataGo/cpp/katago /usr/local/bin/katago
COPY requirements.txt /app/requirements.txt
RUN pip3 install --no-cache-dir --break-system-packages -r requirements.txt
COPY katago_cloud /app/katago_cloud
ENV PYTHONUNBUFFERED=1 \
    KATAGO_BIN=/usr/local/bin/katago \
    KATAGO_CONFIG=/data/analysis.cfg \
    KATAGO_MODEL=/models/model.bin.gz \
    HOST=0.0.0.0 PORT=8080
EXPOSE 8080
USER 10001:10001
CMD ["sh", "-c", "uvicorn katago_cloud.main:app --host ${HOST} --port ${PORT} --workers 1"]
