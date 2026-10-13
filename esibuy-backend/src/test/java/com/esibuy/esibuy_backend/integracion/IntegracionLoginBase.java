package com.esibuy.esibuy_backend.integracion;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Configuración común de las pruebas de integración del login: apuntan al MongoDB compartido ({@link MongoCompartido}),
 * usan un pepper de pruebas y un límite de peticiones del registro que no interfiera. Cada clase que herede de esta
 * lleva @Testcontainers(disabledWithoutDocker = true) para que, sin Docker, se omita en lugar de fallar.
 *
 * <p>Las propiedades se evalúan cuando Spring crea el contexto, es decir, solo si la clase no se ha omitido.
 */
abstract class IntegracionLoginBase {

    @DynamicPropertySource
    static void configurarPropiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.mongodb.uri", () -> MongoCompartido.contenedor().getReplicaSetUrl("ESIBuy"));
        registro.add("esibuy.seguridad.pepper", () -> "pepper-de-integracion");
        // El limite de peticiones del registro no debe interferir con estas pruebas
        registro.add("esibuy.limite-registro.max-peticiones", () -> "1000");
    }
}
