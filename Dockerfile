FROM debian:latest

RUN apt-get update && apt-get install -y \
    openssh-client \
    libcurl4-gnutls-dev \
    imagemagick \
    && rm -rf /var/lib/apt/lists/*

COPY ImmichFrameFeeder.kexe /application
RUN chmod 755 /application
CMD ["/application"]
