FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-client \
    libcurl4-gnutls-dev \
    imagemagick \
    libmagickcore-6.q16-6-extra \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -d /home/appuser -m appuser

# Disable dangerous ImageMagick coders
RUN if [ -f /etc/ImageMagick-6/policy.xml ]; then \
      sed -i '/<\/policymap>/i \  <policy domain="coder" rights="none" pattern="{TEXT,SHOW,WIN,PLT,MSL,MVG,EPHEMERAL,URL,HTTPS,HTTP,FTP}" />' /etc/ImageMagick-6/policy.xml; \
    fi

COPY ImmichFrameFeeder.kexe /application
RUN chmod 755 /application

USER appuser
CMD ["/application"]
