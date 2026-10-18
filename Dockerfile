FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-client \
    libcurl4-gnutls-dev \
    imagemagick \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY ImmichFrameFeeder.kexe /application
RUN chmod 755 /application
CMD ["/application"]
