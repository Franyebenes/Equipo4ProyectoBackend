package com.esibuy.esibuy_backend.modelo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import com.esibuy.esibuy_backend.servicio.GeneradorTotp;

/*
 * Estado del segundo factor (TOTP) de un usuario: el subdocumento "dosFactores" de la coleccion usuarios. Un usuario que
 * no lo tiene (todos los anteriores a la HdU) sigue con un solo factor.
 *
 * Tiene dos formas: pendiente (la persona ha empezado la activacion y aun no ha confirmado el primer codigo: solo existe
 * el secreto pendiente) y activo (secreto definitivo, ultimo paso aceptado y codigos de recuperacion). Los secretos son
 * siempre el texto cifrado que produce CifradorSecretos ("version:base64url") y los codigos de recuperacion solo existen
 * como hash: nada en claro, ni en el modelo ni en la base de datos (cuyo esquema lo exige tambien).
 *
 * Esta clase solo representa el estado. Los cambios, que deben ser atomicos, los hace RepositorioDosFactores.
 * Tests: DosFactoresTest (CP-2FA-89 a CP-2FA-99).
 */
public class DosFactores {

    // Cuantos codigos de recuperacion admite como maximo el esquema de la base de datos.
    public static final int MAXIMO_CODIGOS = 20;

    // "version:base64url", el formato de CifradorSecretos
    private static final Pattern FORMATO_SECRETO_CIFRADO = Pattern.compile("^[0-9]{1,9}:[A-Za-z0-9_-]+$");
    // SHA-256 / HMAC-SHA-256 en hexadecimal minuscula
    private static final Pattern FORMATO_HASH = Pattern.compile("^[0-9a-f]{64}$");

    private boolean activo;
    private String secretoCifrado;
    private Pendiente pendiente;
    private Long ultimoPaso;
    private Instant activadoEn;
    private List<CodigoRecuperacion> codigosRecuperacion;

    /** Usado por Spring Data al leer de MongoDB. */
    protected DosFactores() {
    }

    /** Activacion empezada y sin confirmar: solo el secreto pendiente. */
    public static DosFactores pendiente(String secretoCifrado, Instant creadoEn) {
        DosFactores dosFactores = new DosFactores();
        dosFactores.pendiente = new Pendiente(exigirSecretoCifrado(secretoCifrado), exigirInstante(creadoEn));
        return dosFactores;
    }

    /** Segundo factor activo. ultimoPaso es GeneradorTotp.NINGUN_PASO si aun no se ha aceptado ningun codigo. */
    public static DosFactores activo(String secretoCifrado, Instant activadoEn, long ultimoPaso,
                                     List<CodigoRecuperacion> codigos) {
        if (ultimoPaso < GeneradorTotp.NINGUN_PASO) {
            throw new IllegalArgumentException("El ultimo paso no puede ser anterior a NINGUN_PASO");
        }
        if (codigos == null || codigos.size() > MAXIMO_CODIGOS) {
            throw new IllegalArgumentException("Los codigos de recuperacion son obligatorios y como mucho " + MAXIMO_CODIGOS);
        }
        DosFactores dosFactores = new DosFactores();
        dosFactores.activo = true;
        dosFactores.secretoCifrado = exigirSecretoCifrado(secretoCifrado);
        dosFactores.activadoEn = exigirInstante(activadoEn);
        dosFactores.ultimoPaso = ultimoPaso == GeneradorTotp.NINGUN_PASO ? null : ultimoPaso;
        dosFactores.codigosRecuperacion = List.copyOf(codigos);
        return dosFactores;
    }

    /** Falla si el texto no es "version:base64url": asi un secreto en claro (un Base32, por ejemplo) no llega a guardarse. */
    public static String exigirSecretoCifrado(String secretoCifrado) {
        if (secretoCifrado == null || !FORMATO_SECRETO_CIFRADO.matcher(secretoCifrado).matches()) {
            throw new IllegalArgumentException("El secreto no tiene el formato del texto cifrado");
        }
        return secretoCifrado;
    }

    /** Falla si el texto no es un hash SHA-256 en hexadecimal minuscula: un codigo de recuperacion en claro no pasa. */
    public static String exigirHash(String hash) {
        if (hash == null || !FORMATO_HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("El hash no tiene el formato SHA-256 en hexadecimal");
        }
        return hash;
    }

    private static Instant exigirInstante(Instant instante) {
        if (instante == null) {
            throw new IllegalArgumentException("La fecha es obligatoria");
        }
        return instante;
    }

    public boolean isActivo() {
        return activo;
    }

    /** Texto cifrado del secreto definitivo; null si el 2FA no esta activo. */
    public String getSecretoCifrado() {
        return secretoCifrado;
    }

    /** La activacion sin confirmar, si la hay. */
    public Pendiente getPendiente() {
        return pendiente;
    }

    /** Ultimo paso TOTP aceptado, o GeneradorTotp.NINGUN_PASO si ninguno. */
    public long getUltimoPaso() {
        return ultimoPaso == null ? GeneradorTotp.NINGUN_PASO : ultimoPaso;
    }

    public Instant getActivadoEn() {
        return activadoEn;
    }

    public List<CodigoRecuperacion> getCodigosRecuperacion() {
        return codigosRecuperacion == null ? List.of() : List.copyOf(codigosRecuperacion);
    }

    /** Cuantos codigos de recuperacion quedan sin usar. */
    public int codigosDisponibles() {
        return (int) getCodigosRecuperacion().stream().filter(codigo -> !codigo.isUsado()).count();
    }

    // Sin el secreto ni los hashes: una traza no debe poder filtrarlos
    @Override
    public String toString() {
        return "DosFactores[activo=" + activo + ", pendiente=" + (pendiente != null)
                + ", codigosDisponibles=" + codigosDisponibles() + "]";
    }

    /** Secreto TOTP de una activacion que aun no se ha confirmado con un primer codigo. */
    public static class Pendiente {

        private String secretoCifrado;
        private Instant creadoEn;

        /** Usado por Spring Data al leer de MongoDB. */
        protected Pendiente() {
        }

        private Pendiente(String secretoCifrado, Instant creadoEn) {
            this.secretoCifrado = secretoCifrado;
            this.creadoEn = creadoEn;
        }

        public String getSecretoCifrado() {
            return secretoCifrado;
        }

        public Instant getCreadoEn() {
            return creadoEn;
        }

        /** Vale mientras ahora sea anterior a creadoEn + caducidad: a la caducidad exacta ya no. */
        public boolean haCaducado(Instant ahora, Duration caducidad) {
            return !ahora.isBefore(creadoEn.plus(caducidad));
        }

        @Override
        public String toString() {
            return "Pendiente[creadoEn=" + creadoEn + "]";
        }
    }

    /** Un codigo de recuperacion: solo su hash, y si ya se uso. */
    public static class CodigoRecuperacion {

        private String hash;
        private boolean usado;
        private Instant usadoEn;

        /** Usado por Spring Data al leer de MongoDB. */
        protected CodigoRecuperacion() {
        }

        private CodigoRecuperacion(String hash) {
            this.hash = hash;
        }

        /** Un codigo sin usar con ese hash. */
        public static CodigoRecuperacion nuevo(String hash) {
            return new CodigoRecuperacion(exigirHash(hash));
        }

        public String getHash() {
            return hash;
        }

        public boolean isUsado() {
            return usado;
        }

        public Instant getUsadoEn() {
            return usadoEn;
        }

        @Override
        public String toString() {
            return "CodigoRecuperacion[usado=" + usado + "]";
        }
    }
}
