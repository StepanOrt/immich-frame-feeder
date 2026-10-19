FROM debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587

RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-client \
    libcurl4-gnutls-dev \
    imagemagick \
    libmagickcore-6.q16-6-extra \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# Note: libcurl4-gnutls-dev provides the libcurl.so shared library required by ktor-client-curl on Linux X64

RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -d /home/appuser -m appuser

# Disable dangerous ImageMagick coders
RUN if [ -f /etc/ImageMagick-6/policy.xml ]; then \
      sed -i '/<\/policymap>/i \  <policy domain="coder" rights="none" pattern="{TEXT,SHOW,WIN,PLT,MSL,MVG,EPHEMERAL,URL,HTTPS,HTTP,FTP}" />' /etc/ImageMagick-6/policy.xml; \
    fi

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh

COPY ImmichFrameFeeder.kexe /application
RUN chmod 755 /application

ENTRYPOINT ["/entrypoint.sh"]
CMD ["/application"]
