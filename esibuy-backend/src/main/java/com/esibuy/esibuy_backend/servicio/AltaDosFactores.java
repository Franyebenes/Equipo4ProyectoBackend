package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;

/*
 * Segundo factor de una cuenta que se esta registrando: comprueba el primer codigo contra el secreto pendiente de la sesion y
 * construye el 2FA ya activo de la cuenta nueva. Tests: AltaDosFactoresTest (CP-2FA-442 a CP-2FA-456).
 *
 * verificar acepta el paso actual y uno a cada lado (como el login); todavia no hay cuenta, asi que no hay "ultimo paso" con el
 * que comparar y se devuelve el paso aceptado para dejarlo como ultimo paso de la cuenta. crear vuelve a cifrar el secreto con el
 * id de la cuenta como dato asociado (el del registro estaba ligado al correo), genera los codigos de recuperacion (en la cuenta
 * solo queda su hash) y los devuelve en claro, una sola vez. Un fallo al descifrar es un 503 sin detalles y sin mas log que el
 * nombre de la clase de la excepcion: el mensaje podria contener datos sensibles.
 */
@Component
public class AltaDosFactores {

    private static final Logger log = LoggerFactory.getLogger(AltaDosFactores.class);

    private static final String CAMPO_CODIGO = "codigoDosFactores";

    /** El secreto que el codigo ha verificado y el paso TOTP que se ha aceptado. */
    public record Verificado(byte[] secreto, long paso) {

        // Un record compara los arrays por referencia: aqui se compara su contenido
        @Override
        public boolean equals(Object otro) {
            return otro instanceof Verificado v && paso == v.paso && Arrays.equals(secreto, v.secreto);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(secreto) + Long.hashCode(paso);
        }

        @Override
        public String toString() {
            return "Verificado[OCULTO]";
        }
    }

    /** El 2FA activo para guardar en la cuenta y los codigos de recuperacion en claro (formato para mostrar), una sola vez. */
    public record DosFactoresNuevos(DosFactores dosFactores, List<String> codigosRecuperacion) {

        @Override
        public String toString() {
            return "DosFactoresNuevos[OCULTO]";
        }
    }

    private final CifradorSecretos cifrador;
    private final GeneradorTotp totp;
    private final GeneradorCodigosRecuperacion generadorCodigos;
    private final PropiedadesDosFactores propiedades;
    private final Clock reloj;

    public AltaDosFactores(CifradorSecretos cifrador, GeneradorTotp totp, GeneradorCodigosRecuperacion generadorCodigos,
            PropiedadesDosFactores propiedades, Clock reloj) {
        this.cifrador = cifrador;
        this.totp = totp;
        this.generadorCodigos = generadorCodigos;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    public Verificado verificar(PendienteRegistroDosFactores pendiente, String codigo) {
        byte[] secreto;
        try {
            secreto = cifrador.descifrar(pendiente.secretoCifrado(), pendiente.email());
        } catch (RuntimeException e) {
            log.error("Fallo interno al verificar el 2FA del registro: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException(null);
        }
        OptionalLong paso = totp.verificar(secreto, codigo, GeneradorTotp.NINGUN_PASO);
        if (paso.isEmpty()) {
            throw new DatosRegistroInvalidosException(Map.of(CAMPO_CODIGO, Set.of(CodigoError.CODIGO_INCORRECTO)));
        }
        return new Verificado(secreto, paso.getAsLong());
    }

    public DosFactoresNuevos crear(Verificado verificado, String idUsuario) {
        if (idUsuario == null || idUsuario.isBlank()) {
            throw new IllegalArgumentException("El id de la cuenta es obligatorio");
        }
        String cifrado;
        List<String> codigos;
        try {
            cifrado = cifrador.cifrar(verificado.secreto(), idUsuario);
            codigos = generadorCodigos.nuevosCodigos(propiedades.codigosRecuperacion());
        } catch (RuntimeException e) {
            log.error("Fallo interno al crear el 2FA del registro: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException(null);
        }
        List<DosFactores.CodigoRecuperacion> guardados = codigos.stream()
                .map(codigo -> DosFactores.CodigoRecuperacion.nuevo(generadorCodigos.hash(codigo))).toList();
        DosFactores dosFactores = DosFactores.activo(cifrado, reloj.instant(), verificado.paso(), guardados);
        return new DosFactoresNuevos(dosFactores, codigos.stream().map(GeneradorCodigosRecuperacion::formatear).toList());
    }
}
