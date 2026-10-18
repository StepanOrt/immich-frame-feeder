FROM debian:latest

RUN apt-get update && apt-get install -y \
    openssh-client \
    libcurl4-gnutls-dev \
    imagemagick \
    && rm -rf /var/lib/apt/lists/*

COPY --chmod=700 KotlinNativeTemplate.kexe /application
CMD ["/application"]
