FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    openjdk-17-jre \
    wget \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/janusgraph

RUN wget -q https://github.com/JanusGraph/janusgraph/releases/download/v0.6.3/janusgraph-0.6.3-hadoop2.zip \
    && unzip janusgraph-0.6.3-hadoop2.zip \
    && rm janusgraph-0.6.3-hadoop2.zip \
    && ln -s janusgraph-0.6.3-hadoop2 janusgraph

ENV PATH=/opt/janusgraph/janusgraph/bin:$PATH

RUN mkdir -p /var/lib/janusgraph

EXPOSE 8182

CMD ["janusgraph.sh", "start"]