FROM alpine:3.16
RUN apk add bash git clang binutils-gold cmake make libgcc musl-dev gcc g++ libc6-compat libtool automake autoconf openjdk11
RUN git clone https://github.com/libuv/libuv && cd libuv && ./autogen.sh && ./configure && make && make install
RUN git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && make install
COPY . /poncho

# sbt
RUN mkdir /sbt
RUN cd /sbt && wget https://github.com/sbt/sbt/releases/download/v1.8.2/sbt-1.8.2.zip
RUN cd /sbt && unzip sbt-1.8.2.zip

# build binary native static
RUN cd /poncho && /sbt/sbt/bin/sbt clean compile
ENV SN_LINK=static
CMD cd /poncho && /sbt/sbt/bin/sbt clean nativeLink
