package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.MetodoPagoAltaDTO;
import com.esibuy.esibuy_backend.dto.MetodoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;

/**
 * Metodos de pago del cliente (HU-24.x). Son "de juguete": no se comprueba que la tarjeta exista, solo que los datos
 * tengan un formato razonable. Se guardan los 4 ultimos digitos; el numero completo no se guarda ni se escribe en logs.
 */
@Service
public class ServicioMetodoPago {

    private static final Logger log = LoggerFactory.getLogger(ServicioMetodoPago.class);

    static final int MAXIMO_POR_CLIENTE = 10;
    private static final int LONGITUD_MAXIMA_TITULAR = 100;
    private static final String PATRON_NUMERO = "\\d{12,19}";
    private static final String PATRON_CADUCIDAD = "(0[1-9]|1[0-2])/\\d{2}";
    private static final String PATRON_CVV = "\\d{3,4}";
    private final RepositorioMetodoPago repositorio;

    public ServicioMetodoPago(RepositorioMetodoPago repositorio) {
        this.repositorio = repositorio;
    }

    // HU-24.1 Ver mis metodos de pago (del mas antiguo al mas nuevo)
    public List<MetodoPagoDTO> listar(String idCliente) {
        return repositorio.buscarPorCliente(new ObjectId(idCliente)).stream()
                .sorted(Comparator.comparing(MetodoPago::getCreadoEn, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ServicioMetodoPago::aDTO)
                .toList();
    }

    // HU-24.2 Anadir metodo de pago
    public MetodoPagoDTO anadir(String idCliente, MetodoPagoAltaDTO datos) {
        if (datos == null) {
            throw new MetodoPagoInvalidoException("Faltan los datos del metodo de pago");
        }
        String titular = datos.titular() == null ? "" : datos.titular().trim();
        if (titular.isEmpty() || titular.length() > LONGITUD_MAXIMA_TITULAR) {
            throw new MetodoPagoInvalidoException("Indica el titular de la tarjeta");
        }
        // Se aceptan espacios y guiones al escribir el numero ("1234 5678 ...")
        String numero = datos.numeroTarjeta() == null ? "" : datos.numeroTarjeta().replaceAll("[\\s-]", "");
        if (!numero.matches(PATRON_NUMERO)) {
            throw new MetodoPagoInvalidoException("El numero de tarjeta debe tener entre 12 y 19 digitos");
        }
        String caducidad = datos.caducidad() == null ? "" : datos.caducidad().trim();
        if (!caducidad.matches(PATRON_CADUCIDAD)) {
            throw new MetodoPagoInvalidoException("La caducidad debe tener el formato MM/AA");
        }
                // El CVV se comprueba como en un pago normal, pero no se guarda nunca
        String cvv = datos.cvv() == null ? "" : datos.cvv().trim();
        if (!cvv.matches(PATRON_CVV)) {
            throw new MetodoPagoInvalidoException("El CVV debe tener 3 o 4 digitos");
        }
        if (repositorio.contarPorCliente(new ObjectId(idCliente)) >= MAXIMO_POR_CLIENTE) {
            throw new MetodoPagoInvalidoException("Como maximo puedes guardar " + MAXIMO_POR_CLIENTE + " metodos de pago");
        }
        String ultimosDigitos = numero.substring(numero.length() - 4);
        MetodoPago guardado = repositorio.save(
                new MetodoPago(idCliente, titular, ultimosDigitos, caducidad, Instant.now()));
        log.info("Metodo de pago anadido: cliente={} ultimosDigitos={}", idCliente, ultimosDigitos);
        return aDTO(guardado);
    }

    // HU-24.3 Eliminar metodo de pago (solo uno propio)
    public void eliminar(String idCliente, String idMetodoPago) {
        if (idMetodoPago == null || !ObjectId.isValid(idMetodoPago)) {
            throw new MetodoPagoNoEncontradoException();
        }
        MetodoPago metodo = repositorio.buscarDelCliente(new ObjectId(idMetodoPago), new ObjectId(idCliente))
                .orElseThrow(MetodoPagoNoEncontradoException::new);
        repositorio.delete(metodo);
        log.info("Metodo de pago eliminado: cliente={} id={}", idCliente, idMetodoPago);
    }

    private static MetodoPagoDTO aDTO(MetodoPago metodo) {
        return new MetodoPagoDTO(metodo.getId(), metodo.getTitular(), metodo.getUltimosDigitos(), metodo.getCaducidad());
    }
}