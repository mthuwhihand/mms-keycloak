FROM eclipse-temurin:17-jdk-alpine

# Cài unzip vì alpine không có sẵn
RUN apk add --no-cache unzip bash

# Tạo thư mục làm việc
WORKDIR /opt/keycloak

# Copy file zip đã build
COPY target/keycloak-22.0.1.zip .

# Giải nén, move file vào thư mục làm việc
RUN unzip keycloak-22.0.1.zip && \
    rm keycloak-22.0.1.zip && \
    mv keycloak-*/* . && \
    rmdir keycloak-*

# Mở port 8080
EXPOSE 8080

# Chạy Keycloak
ENTRYPOINT ["bin/kc.sh"]
CMD ["start-dev"]
