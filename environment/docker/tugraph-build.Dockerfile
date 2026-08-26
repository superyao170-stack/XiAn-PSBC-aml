FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    wget \
    curl \
    libssl-dev \
    libgflags-dev \
    libgoogle-glog-dev \
    libprotobuf-dev \
    protobuf-compiler \
    libboost-all-dev \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

COPY tugraph-4.5.2-1.x86_64.deb /tmp/

RUN dpkg -i /tmp/tugraph-4.5.2-1.x86_64.deb || apt-get install -yf

RUN mkdir -p /home/tugraph/storage /home/tugraph/etc

COPY lgraph.json /home/tugraph/etc/lgraph.json

EXPOSE 7687 9090

CMD ["lgraph_server", "-c", "/home/tugraph/etc/lgraph.json"]