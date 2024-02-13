FROM ubuntu:22.04

RUN mkdir -p /home/tbar

RUN apt-get update && apt-get install -y maven build-essential subversion openjdk-8-jdk git cpanminus curl unzip 
RUN update-alternatives --set java /usr/lib/jvm/java-8-openjdk-arm64/jre/bin/java
WORKDIR "/home/tbar"

RUN apt-get install -y locales
RUN locale-gen en_US.UTF-8

RUN git clone https://github.com/rjust/defects4j.git
WORKDIR "/home/tbar/defects4j"
RUN ./init.sh

ENV PATH=$PATH:"/home/tbar/defects4j/framework/bin"