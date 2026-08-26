FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    libssl-dev \
    libcurl4-openssl-dev \
    libgflags-dev \
    libgoogle-glog-dev \
    libprotobuf-dev \
    protobuf-compiler \
    libboost-all-dev \
    python3 \
    python3-pip \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip3 install wheel

WORKDIR /opt/tugraph

RUN git clone --depth 1 --branch 3.6.0 https://github.com/TuGraph-family/tugraph-db.git

WORKDIR /opt/tugraph/tugraph-db/build

RUN cmake .. -DCMAKE_BUILD_TYPE=Release -DENABLE_PARALLEL=ON \
    && make -j$(nproc) \
    && make install

WORKDIR /opt/tugraph

RUN mkdir -p /home/tugraph/storage /home/tugraph/etc

COPY --from=tugraph/tugraph-runtime:3.6.0 /home/tugraph/etc/lgraph.json /home/tugraph/etc/lgraph.json

ENV PATH=/usr/local/bin:$PATH

EXPOSE 7687 9090

CMD ["lgraph_server", "-c", "/home/tugraph/etc/lgraph.json"]