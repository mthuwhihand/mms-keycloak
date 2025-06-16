FROM eclipse-temurin:17-jdk as builder

WORKDIR /opt/keycloak

COPY . .

RUN ./mvnw clean install -DskipTests

# Stage 2: create actual image
FROM quay.io/keycloak/keycloak:latest

COPY --from=builder /opt/keycloak/quarkus/dist/target/keycloak-*-dist.tar.gz /opt/keycloak/

WORKDIR /opt/keycloak

RUN tar -xzf keycloak-*-dist.tar.gz && \
    mv keycloak-* keycloak && \
    rm keycloak-*-dist.tar.gz

ENV KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

ENTRYPOINT ["/opt/keycloak/keycloak/bin/kc.sh"]
CMD ["start-dev"]
