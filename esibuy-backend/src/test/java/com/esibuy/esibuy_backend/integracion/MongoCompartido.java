package com.esibuy.esibuy_backend.integracion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Un único contenedor MongoDB (con el script de BBDD del proyecto aplicado) para todas las pruebas de integración del
 * login. Se arranca la primera vez que hace falta y se mantiene hasta que acaba la ejecución de las pruebas (la
 * herramienta de contenedores lo retira al terminar). Así no se levanta un contenedor por clase, y las clases que
 * comparten el mismo contexto de Spring apuntan siempre a la misma base de datos.
 */
final class MongoCompartido {

    private static MongoDBContainer contenedor;

    private MongoCompartido() {
    }

    static synchronized MongoDBContainer contenedor() {
        if (contenedor == null) {
            MongoDBContainer nuevo = new MongoDBContainer("mongo:7.0").withCopyFileToContainer(
                    MountableFile.forClasspathResource("mongo/init-esibuy.js"), "/tmp/init-esibuy.js");
            nuevo.start();
            aplicarElScriptDeBaseDeDatos(nuevo);
            contenedor = nuevo;
        }
        return contenedor;
    }

    private static void aplicarElScriptDeBaseDeDatos(MongoDBContainer mongo) {
        try {
            ExecResult resultado = mongo.execInContainer("mongosh", "--quiet", "--file", "/tmp/init-esibuy.js");
            assertEquals(0, resultado.getExitCode(), "Fallo al ejecutar el script de BBDD: " + resultado.getStderr());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo ejecutar el script de BBDD en el contenedor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al ejecutar el script de BBDD", e);
        }
    }
}
